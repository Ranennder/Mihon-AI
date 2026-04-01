#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ctypes
import json
import itertools
import locale
import mimetypes
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from PIL import Image


DEFAULT_CONFIG_NAME = "reader_ai_server.json"
RUNTIME_BINARY_ALIASES = (
    "realesrgan-ncnn-vulkan.exe",
    "realesrgan-ncnn.exe",
    "realsr-ncnn.exe",
)
MODEL_DIR_ALIASES = (
    "models",
    "models-Real-ESRGANv3-anime",
)
SUPPORTED_MODELS = {
    "realesr-animevideov3": {
        "native_scale": 2,
    },
    "realesrgan-x4plus-anime": {
        "native_scale": 4,
    },
    "realesrgan-x4plus": {
        "native_scale": 4,
    },
}
SUPPORTED_MODEL_NAMES = tuple(SUPPORTED_MODELS.keys())
SUPPORTED_BATCH_SIZES = (1, 4)
JPEG_MAX_DIMENSION = 65535
JPEG_OUTPUT_FORMATS = {"jpg", "jpeg"}
PNG_OUTPUT_FORMAT = "png"
INTERNAL_CHUNK_FORMAT = "png"
JPEG_QUALITY = 95
MAX_SINGLE_OUTPUT_DIMENSION = 65535
MAX_CHUNK_OUTPUT_DIMENSION = 16384
CHUNK_OVERLAP_INPUT_PIXELS = 32
LOG_FILE_NAME = "companion.log"
_STOP_PROCESSING = object()
_LOG_LOCK = threading.Lock()
_LOG_FILE_HANDLE: Any | None = None
_CONSOLE_STYLING_ENABLED = False
_LOG_TIMESTAMP_RE = re.compile(r"^\[(\d{2}/[A-Za-z]{3}/\d{4} \d{2}:\d{2}:\d{2})\]\s+(.*)$")
_LOG_REQUEST_RE = re.compile(r"^\[(req-[^\]]+)\]\s+(.*)$")
_PROGRESS_RE = re.compile(r"^(?P<percent>\d+(?:[.,]\d+)?)%$")
_ANSI_RESET = "\x1b[0m"
_ANSI_DIM = "\x1b[2m"
_ANSI_BOLD = "\x1b[1m"
_ANSI_RED = "\x1b[91m"
_ANSI_GREEN = "\x1b[92m"
_ANSI_YELLOW = "\x1b[93m"
_ANSI_BLUE = "\x1b[94m"
_ANSI_MAGENTA = "\x1b[95m"
_ANSI_CYAN = "\x1b[96m"
_ANSI_WHITE = "\x1b[97m"
_ANSI_ESCAPE_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
_INLINE_PROGRESS_ACTIVE = False
_INLINE_PROGRESS_VISIBLE_LENGTH = 0

Image.MAX_IMAGE_PIXELS = None


def _bundled_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).resolve().parent))
    return Path(__file__).resolve().parent


def _app_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


@dataclass
class Config:
    host: str = "0.0.0.0"
    port: int = 8765
    token: str = ""
    mode: str = "subprocess"
    binary: str = "runtime/realesrgan-ncnn.exe"
    model_dir: str = "runtime/models"
    model_name: str = "realesr-animevideov3"
    output_format: str = "jpg"
    scale: int = 2
    tile_size: int = 0
    gpu_id: int = 0
    jobs: str = "1:2:2"
    timeout_seconds: int = 180
    max_workers: int = 1
    batch_size: int = 1
    batch_wait_milliseconds: int = 200
    max_request_megabytes: int = 32

    @classmethod
    def load(cls, config_path: Path, overrides: dict[str, Any]) -> "Config":
        data: dict[str, Any] = {}
        base_dir = _app_root()
        if config_path.is_file():
            data = json.loads(config_path.read_text(encoding="utf-8-sig"))
            base_dir = config_path.parent

        merged = {**data, **{key: value for key, value in overrides.items() if value is not None}}
        config = cls(**merged)
        config.batch_size = _normalize_batch_size(config.batch_size)
        config.batch_wait_milliseconds = max(0, int(config.batch_wait_milliseconds))
        config.binary = str(_resolve_runtime_binary(config.binary, base_dir))
        config.model_dir = str(_resolve_model_dir(config.model_dir, base_dir))
        return config


@dataclass(frozen=True)
class ProcessedImage:
    bytes: bytes
    output_format: str


@dataclass
class PendingUpscaleRequest:
    request_id: str
    body: bytes
    input_format: str
    requested_output_format: str
    model_name: str
    native_scale: int
    preferred_output_format: str | None
    batch_size: int
    is_chunked: bool
    enqueued_at: float = field(default_factory=time.perf_counter)
    completed: threading.Event = field(default_factory=threading.Event, repr=False)
    result: ProcessedImage | None = None
    error: Exception | None = None

    @property
    def batch_key(self) -> tuple[str, str, int] | None:
        if self.is_chunked or self.preferred_output_format is None or self.batch_size <= 1:
            return None
        return (self.model_name, self.preferred_output_format, self.native_scale)


@dataclass(frozen=True)
class SubprocessRunResult:
    returncode: int
    details: str


@dataclass(frozen=True)
class ImageSize:
    width: int
    height: int


class ReaderAiServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, server_address: tuple[str, int], config: Config):
        super().__init__(server_address, ReaderAiRequestHandler)
        self.config = config
        self._workspace_root_owner = tempfile.TemporaryDirectory(prefix="mihon-ai-server-")
        self._workspace_root = Path(self._workspace_root_owner.name)
        self._request_sequence = itertools.count(1)
        self._processing_queue: queue.Queue[Any] = queue.Queue()
        self._worker_threads: list[threading.Thread] = []
        for worker_index in range(max(1, config.max_workers)):
            workspace = self._workspace_root / f"worker-{worker_index}"
            workspace.mkdir(parents=True, exist_ok=True)
            thread = threading.Thread(
                target=self._worker_loop,
                args=(workspace,),
                name=f"reader-ai-worker-{worker_index}",
                daemon=True,
            )
            thread.start()
            self._worker_threads.append(thread)

    def process_image(
        self,
        request_id: str,
        body: bytes,
        input_format: str,
        output_format: str,
        model_name: str,
        batch_size: int,
    ) -> ProcessedImage:
        if self.config.mode == "mock_copy":
            return ProcessedImage(body, output_format)

        native_scale = _resolve_model_scale(
            model_name=model_name,
            default_scale=self.config.scale,
        )
        target_scale = native_scale
        is_chunked = _requires_chunked_upscale(
            body=body,
            native_scale=native_scale,
            target_scale=target_scale,
        )
        preferred_output_format = None if is_chunked else _select_output_format(
            body=body,
            requested_output_format=output_format,
            scale=native_scale,
        )

        request = PendingUpscaleRequest(
            request_id=request_id,
            body=body,
            input_format=input_format,
            requested_output_format=output_format,
            model_name=model_name,
            native_scale=native_scale,
            preferred_output_format=preferred_output_format,
            batch_size=batch_size,
            is_chunked=is_chunked,
        )
        self._processing_queue.put(request)
        request.completed.wait()

        if request.error is not None:
            raise request.error
        if request.result is None:
            raise RuntimeError("Upscale request completed without a result")
        return request.result

    def next_request_id(self) -> str:
        return f"req-{next(self._request_sequence):05d}"

    def _worker_loop(self, workspace: Path) -> None:
        backlog: list[Any] = []
        while True:
            request = self._take_next_request(backlog)
            if request is _STOP_PROCESSING:
                return

            batch = [request]
            try:
                if request.batch_key is not None:
                    batch = self._collect_batch(request, backlog)
                    if len(batch) > 1:
                        _emit_log_line(
                            (
                                f"[{time.strftime('%d/%b/%Y %H:%M:%S')}] "
                                f"Starting remote batch of {len(batch)} requests "
                                f"(size={request.batch_size}, wait={self._describe_batch_wait(batch)}): "
                                f"{', '.join(item.request_id for item in batch)}"
                            ),
                        )
                    results = _run_subprocess_batch(
                        config=self.config,
                        workspace=workspace,
                        requests=batch,
                    )
                    for item in batch:
                        item.result = results[item.request_id]
                else:
                    request.result = _process_single_request_in_workspace(
                        config=self.config,
                        workspace=workspace,
                        request=request,
                    )
            except Exception as exc:  # noqa: BLE001
                for item in batch:
                    item.error = exc
            finally:
                for item in batch:
                    item.completed.set()

    def _collect_batch(
        self,
        first_request: PendingUpscaleRequest,
        backlog: list[Any],
    ) -> list[PendingUpscaleRequest]:
        batch = [first_request]
        deadline = time.perf_counter() + (self.config.batch_wait_milliseconds / 1000)
        while len(batch) < first_request.batch_size:
            remaining = deadline - time.perf_counter()
            if remaining <= 0:
                break
            next_request = self._take_next_request(backlog, timeout=remaining)
            if next_request is None:
                break
            if next_request is _STOP_PROCESSING:
                backlog.append(next_request)
                break
            if next_request.batch_key != first_request.batch_key:
                backlog.append(next_request)
                break
            batch.append(next_request)
        return batch

    def _take_next_request(
        self,
        backlog: list[Any],
        timeout: float | None = None,
    ) -> Any:
        if backlog:
            return backlog.pop(0)
        try:
            return self._processing_queue.get(timeout=timeout) if timeout is not None else self._processing_queue.get()
        except queue.Empty:
            return None

    def _describe_batch_wait(self, batch: list[PendingUpscaleRequest]) -> str:
        waits = [max(0, int((time.perf_counter() - item.enqueued_at) * 1000)) for item in batch]
        if not waits:
            return "0ms"
        return f"{min(waits)}-{max(waits)}ms"

    def server_close(self) -> None:
        try:
            super().server_close()
        finally:
            for _ in self._worker_threads:
                self._processing_queue.put(_STOP_PROCESSING)
            for worker_thread in self._worker_threads:
                worker_thread.join(timeout=1)
            self._workspace_root_owner.cleanup()

    def handle_error(self, request: Any, client_address: tuple[str, int]) -> None:
        _, exc, _ = sys.exc_info()
        if isinstance(exc, ConnectionResetError):
            _emit_log_line(f"[{time.strftime('%d/%b/%Y %H:%M:%S')}] Client disconnected: {client_address[0]}:{client_address[1]}")
            return
        super().handle_error(request, client_address)


class ReaderAiRequestHandler(BaseHTTPRequestHandler):
    server: ReaderAiServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path != "/health":
            self._send_text(HTTPStatus.NOT_FOUND, "Not found")
            return

        payload = {
            "ok": True,
            "mode": self.server.config.mode,
            "binary": self.server.config.binary,
            "model_dir": self.server.config.model_dir,
            "output_format": self.server.config.output_format,
            "scale": self.server.config.scale,
            "tile_size": self.server.config.tile_size,
            "gpu_id": self.server.config.gpu_id,
            "model_name": self.server.config.model_name,
            "batch_size": self.server.config.batch_size,
            "batch_wait_milliseconds": self.server.config.batch_wait_milliseconds,
            "supported_models": list(SUPPORTED_MODEL_NAMES),
            "model_profiles": SUPPORTED_MODELS,
        }
        self._send_json(HTTPStatus.OK, payload)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path != "/api/upscale":
            self._send_text(HTTPStatus.NOT_FOUND, "Not found")
            return

        if not self._authorize():
            self._send_text(HTTPStatus.FORBIDDEN, "Invalid or missing X-Reader-AI-Token")
            return

        content_length = self.headers.get("Content-Length")
        if not content_length:
            self._send_text(HTTPStatus.LENGTH_REQUIRED, "Content-Length is required")
            return

        try:
            body_size = int(content_length)
        except ValueError:
            self._send_text(HTTPStatus.BAD_REQUEST, "Content-Length must be an integer")
            return

        if body_size <= 0:
            self._send_text(HTTPStatus.BAD_REQUEST, "Request body is empty")
            return

        if body_size > self.server.config.max_request_megabytes * 1024 * 1024:
            self._send_text(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large")
            return

        input_format = (self.headers.get("X-Reader-AI-Input-Format") or "jpg").lower().strip(".")
        output_format = (self.headers.get("X-Reader-AI-Output-Format") or self.server.config.output_format).lower().strip(".")
        requested_model_name = (self.headers.get("X-Reader-AI-Model-Name") or "").strip()
        requested_batch_size = (self.headers.get("X-Reader-AI-Batch-Size") or "").strip()
        if output_format not in {"jpg", "jpeg", "png", "webp"}:
            self._send_text(HTTPStatus.BAD_REQUEST, "Unsupported output format")
            return

        try:
            model_name = _resolve_requested_model_name(
                requested_model_name=requested_model_name,
                default_model_name=self.server.config.model_name,
            )
        except ValueError as exc:
            self._send_text(HTTPStatus.BAD_REQUEST, str(exc))
            return

        try:
            batch_size = _resolve_requested_batch_size(
                requested_batch_size=requested_batch_size,
                default_batch_size=self.server.config.batch_size,
            )
        except ValueError as exc:
            self._send_text(HTTPStatus.BAD_REQUEST, str(exc))
            return

        request_id = self.server.next_request_id()
        self.log_message(
            "[%s] Accepted upscale request: %d bytes input=%s output=%s model=%s batch=%d from %s",
            request_id,
            body_size,
            input_format,
            output_format,
            model_name,
            batch_size,
            self.client_address[0],
        )

        started_at = time.perf_counter()
        body = self.rfile.read(body_size)
        try:
            processed_image = self.server.process_image(request_id, body, input_format, output_format, model_name, batch_size)
        except subprocess.TimeoutExpired as exc:
            self.log_message("[%s] Upscale timeout after %s seconds", request_id, exc.timeout)
            self._send_text(HTTPStatus.GATEWAY_TIMEOUT, f"Upscale process timed out after {exc.timeout} seconds")
            return
        except Exception as exc:  # noqa: BLE001
            self.log_message("[%s] Upscale error: %s", request_id, exc)
            self._send_text(HTTPStatus.BAD_GATEWAY, str(exc))
            return

        elapsed = time.perf_counter() - started_at
        self.log_message(
            "[%s] Processed %d bytes into %d bytes in %.2fs via %s (%s -> %s)",
            request_id,
            len(body),
            len(processed_image.bytes),
            elapsed,
            self.server.config.mode,
            model_name,
            processed_image.output_format,
        )
        self._send_binary(HTTPStatus.OK, processed_image.bytes, processed_image.output_format)

    def log_message(self, format: str, *args: Any) -> None:
        _emit_log_line("[%s] %s" % (self.log_date_time_string(), format % args))

    def _authorize(self) -> bool:
        token = self.server.config.token.strip()
        if not token:
            return True
        return self.headers.get("X-Reader-AI-Token", "") == token

    def _send_binary(self, status: HTTPStatus, body: bytes, output_format: str) -> None:
        mime = mimetypes.types_map.get(f".{output_format}", "application/octet-stream")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=True, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status: HTTPStatus, message: str) -> None:
        body = message.encode("utf-8", errors="replace")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _run_subprocess(
    config: Config,
    request_id: str,
    workspace: Path,
    body: bytes,
    input_format: str,
    requested_output_format: str,
    model_name: str,
    scale: int,
) -> ProcessedImage:
    request = PendingUpscaleRequest(
        request_id=request_id,
        body=body,
        input_format=input_format,
        requested_output_format=requested_output_format,
        model_name=model_name,
        native_scale=scale,
        preferred_output_format=None,
        batch_size=1,
        is_chunked=False,
    )
    return _process_single_request_in_workspace(
        config=config,
        workspace=workspace,
        request=request,
    )


def _process_single_request_in_workspace(
    config: Config,
    workspace: Path,
    request: PendingUpscaleRequest,
) -> ProcessedImage:
    if request.is_chunked:
        return _run_chunked_subprocess(
            config=config,
            request_id=request.request_id,
            workspace=workspace,
            body=request.body,
            requested_output_format=request.requested_output_format,
            model_name=request.model_name,
            native_scale=request.native_scale,
            target_scale=request.native_scale,
        )

    binary = Path(config.binary)
    model_dir = Path(config.model_dir)

    if not binary.is_file():
        raise RuntimeError(f"Upscale binary not found: {binary}")
    if not model_dir.is_dir():
        raise RuntimeError(f"Model directory not found: {model_dir}")

    preferred_output_format = request.preferred_output_format or _select_output_format(
        body=request.body,
        requested_output_format=request.requested_output_format,
        scale=request.native_scale,
    )

    try:
        return _run_subprocess_once(
            config=config,
            request_id=request.request_id,
            workspace=workspace,
            binary=binary,
            model_dir=model_dir,
            body=request.body,
            input_format=request.input_format,
            output_format=preferred_output_format,
            model_name=request.model_name,
            scale=request.native_scale,
        )
    except RuntimeError as exc:
        if (
            preferred_output_format in JPEG_OUTPUT_FORMATS
            and request.requested_output_format in JPEG_OUTPUT_FORMATS
            and _should_retry_with_png(str(exc))
        ):
            return _run_subprocess_once(
                config=config,
                request_id=request.request_id,
                workspace=workspace,
                binary=binary,
                model_dir=model_dir,
                body=request.body,
                input_format=request.input_format,
                output_format=PNG_OUTPUT_FORMAT,
                model_name=request.model_name,
                scale=request.native_scale,
            )
        raise


def _run_subprocess_batch(
    config: Config,
    workspace: Path,
    requests: list[PendingUpscaleRequest],
) -> dict[str, ProcessedImage]:
    if not requests:
        return {}

    binary = Path(config.binary)
    model_dir = Path(config.model_dir)
    if not binary.is_file():
        raise RuntimeError(f"Upscale binary not found: {binary}")
    if not model_dir.is_dir():
        raise RuntimeError(f"Model directory not found: {model_dir}")

    _clear_workspace_files(workspace)
    input_dir = workspace / "batch-in"
    output_dir = workspace / "batch-out"
    input_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    batch_output_format = requests[0].preferred_output_format or PNG_OUTPUT_FORMAT
    scale = requests[0].native_scale
    model_name = requests[0].model_name

    stems_by_request_id: dict[str, str] = {}
    for index, request in enumerate(requests, start=1):
        stem = f"{index:02d}-{request.request_id}"
        stems_by_request_id[request.request_id] = stem
        input_path = input_dir / f"{stem}.{_sanitize_extension(request.input_format)}"
        input_path.write_bytes(request.body)

    command = [
        str(binary),
        "-i",
        str(input_dir),
        "-o",
        str(output_dir),
        "-s",
        str(scale),
        "-t",
        str(config.tile_size),
        "-m",
        str(model_dir),
        "-f",
        batch_output_format,
        "-g",
        str(config.gpu_id),
        "-j",
        config.jobs,
    ]
    if model_name and _supports_model_name_flag(binary):
        command.extend(["-n", model_name])

    request_label = ",".join(request.request_id for request in requests)
    completed = _run_logged_subprocess(
        request_id=request_label,
        command=command,
        cwd=binary.parent,
        timeout=config.timeout_seconds,
    )
    details = completed.details
    if completed.returncode != 0:
        raise RuntimeError(f"Upscale process failed: {details}")

    results: dict[str, ProcessedImage] = {}
    missing_requests: list[PendingUpscaleRequest] = []
    for request in requests:
        produced_output_path = _resolve_produced_output_path(
            output_dir / f"{stems_by_request_id[request.request_id]}.{batch_output_format}",
            batch_output_format,
        )
        if produced_output_path is None or produced_output_path.stat().st_size == 0:
            missing_requests.append(request)
            continue
        results[request.request_id] = ProcessedImage(
            bytes=produced_output_path.read_bytes(),
            output_format=_sanitize_extension(produced_output_path.suffix),
        )

    for request in missing_requests:
        fallback_workspace = workspace / f"fallback-{request.request_id}"
        fallback_workspace.mkdir(parents=True, exist_ok=True)
        fallback_request = PendingUpscaleRequest(
            request_id=f"{request.request_id}/fallback",
            body=request.body,
            input_format=request.input_format,
            requested_output_format=request.requested_output_format,
            model_name=request.model_name,
            native_scale=request.native_scale,
            preferred_output_format=request.preferred_output_format,
            batch_size=1,
            is_chunked=False,
        )
        results[request.request_id] = _process_single_request_in_workspace(
            config=config,
            workspace=fallback_workspace,
            request=fallback_request,
        )

    return results


def _run_subprocess_once(
    config: Config,
    request_id: str,
    workspace: Path,
    binary: Path,
    model_dir: Path,
    body: bytes,
    input_format: str,
    output_format: str,
    model_name: str,
    scale: int,
) -> ProcessedImage:
    _clear_workspace_files(workspace)
    input_path = workspace / f"input.{_sanitize_extension(input_format)}"
    output_path = workspace / f"output.{_sanitize_extension(output_format)}"
    input_path.write_bytes(body)

    command = [
        str(binary),
        "-i",
        str(input_path),
        "-o",
        str(output_path),
        "-s",
        str(scale),
        "-t",
        str(config.tile_size),
        "-m",
        str(model_dir),
        "-f",
        output_format,
        "-g",
        str(config.gpu_id),
        "-j",
        config.jobs,
    ]
    if model_name and _supports_model_name_flag(binary):
        command.extend(["-n", model_name])

    completed = _run_logged_subprocess(
        request_id=request_id,
        command=command,
        cwd=binary.parent,
        timeout=config.timeout_seconds,
    )
    details = completed.details

    if completed.returncode != 0:
        raise RuntimeError(f"Upscale process failed: {details}")

    produced_output_path = _resolve_produced_output_path(output_path, output_format)
    if produced_output_path is None or produced_output_path.stat().st_size == 0:
        reason = details or "empty output file"
        raise RuntimeError(f"Upscale process did not produce a valid {output_format} image: {reason}")

    return ProcessedImage(
        bytes=produced_output_path.read_bytes(),
        output_format=_sanitize_extension(produced_output_path.suffix) if produced_output_path != output_path else output_format,
    )


def _resolve_produced_output_path(output_path: Path, requested_output_format: str) -> Path | None:
    candidates = [output_path]
    normalized_requested_format = _sanitize_extension(requested_output_format)
    for alternate_format in ("png", "jpg", "jpeg", "webp"):
        alternate_path = output_path.with_name(f"{output_path.name}.{alternate_format}")
        if alternate_path not in candidates:
            candidates.append(alternate_path)
        if alternate_format == normalized_requested_format:
            continue

    for candidate in candidates:
        if candidate.is_file():
            return candidate

    return None


def _sanitize_extension(value: str) -> str:
    value = value.lower().strip().strip(".")
    return value if value in {"jpg", "jpeg", "png", "webp"} else "png"


def _run_logged_subprocess(
    request_id: str,
    command: list[str],
    cwd: Path,
    timeout: int,
) -> SubprocessRunResult:
    process = subprocess.Popen(
        command,
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_lines: list[str] = []

    def reader() -> None:
        if process.stdout is None:
            return
        for raw_line in process.stdout:
            line = raw_line.rstrip()
            if not line:
                continue
            output_lines.append(line)
            _emit_log_line(
                f"[{time.strftime('%d/%b/%Y %H:%M:%S')}] [{request_id}] {line}",
                console=_should_surface_subprocess_console_line(line),
            )

    reader_thread = threading.Thread(target=reader, name=f"reader-ai-log-{request_id}", daemon=True)
    reader_thread.start()
    try:
        returncode = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        reader_thread.join(timeout=1)
        raise

    reader_thread.join(timeout=1)
    if process.stdout is not None:
        process.stdout.close()
    details = "\n".join(output_lines).strip() or f"exit code {returncode}"
    return SubprocessRunResult(returncode=returncode, details=details)


def _clear_workspace_files(workspace: Path) -> None:
    for path in workspace.iterdir():
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
        elif path.is_file():
            path.unlink(missing_ok=True)


def _should_retry_with_png(message: str) -> bool:
    lowered = message.lower()
    return "encode image" in lowered or "empty output file" in lowered


def _requires_chunked_upscale(body: bytes, native_scale: int, target_scale: int) -> bool:
    image_size = _decode_image_size(body)
    if image_size is None:
        return False

    native_output_width = image_size.width * native_scale
    native_output_height = image_size.height * native_scale
    target_output_width = image_size.width * target_scale
    target_output_height = image_size.height * target_scale
    return (
        native_output_width > MAX_SINGLE_OUTPUT_DIMENSION or
        native_output_height > MAX_SINGLE_OUTPUT_DIMENSION or
        target_output_width > MAX_SINGLE_OUTPUT_DIMENSION or
        target_output_height > MAX_SINGLE_OUTPUT_DIMENSION
    )


def _select_output_format(body: bytes, requested_output_format: str, scale: int) -> str:
    normalized_format = _sanitize_extension(requested_output_format)
    if normalized_format not in JPEG_OUTPUT_FORMATS:
        return normalized_format

    image_size = _decode_image_size(body)
    if image_size is None:
        return normalized_format

    if image_size.width * scale > JPEG_MAX_DIMENSION or image_size.height * scale > JPEG_MAX_DIMENSION:
        return PNG_OUTPUT_FORMAT

    return normalized_format


def _decode_image_size(body: bytes) -> ImageSize | None:
    if len(body) >= 24 and body.startswith(b"\x89PNG\r\n\x1a\n"):
        return ImageSize(
            width=int.from_bytes(body[16:20], "big"),
            height=int.from_bytes(body[20:24], "big"),
        )

    if len(body) >= 10 and body[:3] == b"GIF":
        return ImageSize(
            width=int.from_bytes(body[6:8], "little"),
            height=int.from_bytes(body[8:10], "little"),
        )

    if len(body) >= 4 and body[0] == 0xFF and body[1] == 0xD8:
        return _decode_jpeg_size(body)

    return None


def _decode_jpeg_size(body: bytes) -> ImageSize | None:
    sof_markers = {
        0xC0,
        0xC1,
        0xC2,
        0xC3,
        0xC5,
        0xC6,
        0xC7,
        0xC9,
        0xCA,
        0xCB,
        0xCD,
        0xCE,
        0xCF,
    }
    index = 2
    body_len = len(body)

    while index + 1 < body_len:
        while index < body_len and body[index] != 0xFF:
            index += 1
        while index < body_len and body[index] == 0xFF:
            index += 1
        if index >= body_len:
            break

        marker = body[index]
        index += 1
        if marker in {0xD8, 0xD9} or 0xD0 <= marker <= 0xD7:
            continue
        if marker == 0xDA:
            break
        if index + 1 >= body_len:
            break

        segment_length = int.from_bytes(body[index:index + 2], "big")
        if segment_length < 2 or index + segment_length > body_len:
            break
        if marker in sof_markers and segment_length >= 7:
            segment = body[index + 2:index + segment_length]
            return ImageSize(
                width=int.from_bytes(segment[3:5], "big"),
                height=int.from_bytes(segment[1:3], "big"),
            )

        index += segment_length


def _run_chunked_subprocess(
    config: Config,
    request_id: str,
    workspace: Path,
    body: bytes,
    requested_output_format: str,
    model_name: str,
    native_scale: int,
    target_scale: int,
) -> ProcessedImage:
    binary = Path(config.binary)
    model_dir = Path(config.model_dir)
    final_output_format = _select_output_format(body, requested_output_format, target_scale)

    with Image.open(BytesIO(body)) as source_image_raw:
        source_image = source_image_raw.convert("RGB")

    source_width, source_height = source_image.size
    final_width = source_width * target_scale
    final_height = source_height * target_scale
    final_image = Image.new("RGB", (final_width, final_height))

    chunk_input_height = max(512, MAX_CHUNK_OUTPUT_DIMENSION // max(native_scale, 1))
    overlap_input = min(CHUNK_OVERLAP_INPUT_PIXELS, max(0, chunk_input_height // 4))

    try:
        for core_top in range(0, source_height, chunk_input_height):
            core_bottom = min(source_height, core_top + chunk_input_height)
            chunk_top = max(0, core_top - overlap_input)
            chunk_bottom = min(source_height, core_bottom + overlap_input)

            chunk_image = source_image.crop((0, chunk_top, source_width, chunk_bottom))
            try:
                chunk_bytes = _encode_pil_image(chunk_image, INTERNAL_CHUNK_FORMAT)
            finally:
                chunk_image.close()

            processed_chunk = _run_subprocess_once(
                config=config,
                request_id=f"{request_id}/chunk-{(core_top // chunk_input_height) + 1}",
                workspace=workspace,
                binary=binary,
                model_dir=model_dir,
                body=chunk_bytes,
                input_format=INTERNAL_CHUNK_FORMAT,
                output_format=INTERNAL_CHUNK_FORMAT,
                model_name=model_name,
                scale=native_scale,
            )

            with Image.open(BytesIO(processed_chunk.bytes)) as upscaled_chunk_raw:
                upscaled_chunk = upscaled_chunk_raw.convert("RGB")

            try:
                if native_scale != target_scale:
                    target_chunk_size = (
                        source_width * target_scale,
                        (chunk_bottom - chunk_top) * target_scale,
                    )
                    if upscaled_chunk.size != target_chunk_size:
                        resized_chunk = upscaled_chunk.resize(target_chunk_size, Image.Resampling.LANCZOS)
                        upscaled_chunk.close()
                        upscaled_chunk = resized_chunk

                crop_top = (core_top - chunk_top) * target_scale
                crop_bottom = upscaled_chunk.height - ((chunk_bottom - core_bottom) * target_scale)
                visible_chunk = upscaled_chunk.crop((0, crop_top, upscaled_chunk.width, crop_bottom))
                try:
                    final_image.paste(visible_chunk, (0, core_top * target_scale))
                finally:
                    visible_chunk.close()
            finally:
                upscaled_chunk.close()

        return ProcessedImage(
            bytes=_encode_pil_image(final_image, final_output_format),
            output_format=final_output_format,
        )
    finally:
        final_image.close()
        source_image.close()


def _encode_pil_image(image: Image.Image, output_format: str) -> bytes:
    normalized_format = _sanitize_extension(output_format)
    with BytesIO() as buffer:
        if normalized_format in JPEG_OUTPUT_FORMATS:
            image.convert("RGB").save(buffer, format="JPEG", quality=JPEG_QUALITY)
        elif normalized_format == PNG_OUTPUT_FORMAT:
            image.save(buffer, format="PNG", compress_level=1)
        elif normalized_format == "webp":
            image.save(buffer, format="WEBP", quality=95)
        else:
            image.save(buffer, format="PNG", compress_level=1)
        return buffer.getvalue()


def _supports_model_name_flag(binary: Path) -> bool:
    return binary.name.lower() != "realsr-ncnn.exe"


def _resolve_requested_model_name(requested_model_name: str, default_model_name: str) -> str:
    if not requested_model_name:
        return default_model_name

    if requested_model_name not in SUPPORTED_MODEL_NAMES:
        supported = " | ".join(SUPPORTED_MODEL_NAMES)
        raise ValueError(f"Unsupported model name: {requested_model_name}. Supported values: {supported}")

    return requested_model_name


def _resolve_requested_batch_size(requested_batch_size: str, default_batch_size: int) -> int:
    if not requested_batch_size:
        return _normalize_batch_size(default_batch_size)

    try:
        parsed_value = int(requested_batch_size)
    except ValueError as exc:
        raise ValueError("Unsupported batch size. Supported values: 1 | 4") from exc

    return _normalize_batch_size(parsed_value)


def _normalize_batch_size(value: int) -> int:
    if value not in SUPPORTED_BATCH_SIZES:
        supported = " | ".join(str(item) for item in SUPPORTED_BATCH_SIZES)
        raise ValueError(f"Unsupported batch size: {value}. Supported values: {supported}")
    return value


def _resolve_model_scale(model_name: str, default_scale: int) -> int:
    profile = SUPPORTED_MODELS.get(model_name)
    if profile is None:
        return default_scale
    return int(profile["native_scale"])


def _resolve_path(value: str, base_dir: Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else (base_dir / path).resolve()


def _resolve_config_path(value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path

    app_root = _app_root()
    bundled_root = _bundled_root()
    cwd = Path.cwd().resolve()
    search_roots = [app_root]
    if cwd != app_root:
        search_roots.append(cwd)
    if bundled_root not in search_roots:
        search_roots.append(bundled_root)

    if not getattr(sys, "frozen", False):
        search_roots = [cwd]
        if app_root != cwd and app_root not in search_roots:
            search_roots.append(app_root)
        if bundled_root != cwd and bundled_root not in search_roots:
            search_roots.append(bundled_root)

    for root in search_roots:
        candidate = (root / path).resolve()
        if candidate.is_file():
            return candidate

    return (search_roots[0] / path).resolve()


def _resolve_runtime_binary(value: str, base_dir: Path) -> Path:
    return _resolve_runtime_path(
        value=value,
        base_dir=base_dir,
        aliases=RUNTIME_BINARY_ALIASES,
        exists_predicate=Path.is_file,
    )


def _resolve_model_dir(value: str, base_dir: Path) -> Path:
    return _resolve_runtime_path(
        value=value,
        base_dir=base_dir,
        aliases=MODEL_DIR_ALIASES,
        exists_predicate=Path.is_dir,
    )


def _resolve_runtime_path(
    value: str,
    base_dir: Path,
    aliases: tuple[str, ...],
    exists_predicate: Any,
) -> Path:
    path = _resolve_path(value, base_dir)
    if exists_predicate(path):
        return path

    app_root = _app_root()
    bundled_root = _bundled_root()
    search_parents = [path.parent]
    app_runtime_dir = (app_root / "runtime").resolve()
    if app_runtime_dir not in search_parents:
        search_parents.append(app_runtime_dir)
    bundled_runtime_dir = (bundled_root / "runtime").resolve()
    if bundled_runtime_dir not in search_parents:
        search_parents.append(bundled_runtime_dir)

    names = [path.name, *[alias for alias in aliases if alias != path.name]]
    for parent in search_parents:
        for name in names:
            candidate = parent / name
            if exists_predicate(candidate):
                return candidate

    return path


def _configure_output_tee() -> Path:
    log_path = _app_root() / LOG_FILE_NAME
    global _LOG_FILE_HANDLE
    _LOG_FILE_HANDLE = open(log_path, "a", encoding="utf-8", buffering=1)
    return log_path


def _emit_log_line(message: str, *, console: bool = True, log_file: bool = True) -> None:
    _emit_log_text(message + "\n", console=console, log_file=log_file)


def _should_surface_subprocess_console_line(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    if _PROGRESS_RE.fullmatch(stripped) is not None:
        return True
    return False


def _enable_console_styling() -> None:
    global _CONSOLE_STYLING_ENABLED
    console = getattr(sys, "__stdout__", None) or sys.stdout
    if not getattr(console, "isatty", lambda: False)():
        _CONSOLE_STYLING_ENABLED = False
        return
    if os.name != "nt":
        _CONSOLE_STYLING_ENABLED = True
        return
    try:
        handle = ctypes.windll.kernel32.GetStdHandle(-11)
        if handle in (0, -1):
            _CONSOLE_STYLING_ENABLED = False
            return
        mode = ctypes.c_uint()
        if not ctypes.windll.kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            _CONSOLE_STYLING_ENABLED = False
            return
        enable_virtual_terminal_processing = 0x0004
        processed_output = 0x0001
        new_mode = mode.value | enable_virtual_terminal_processing | processed_output
        _CONSOLE_STYLING_ENABLED = bool(
            ctypes.windll.kernel32.SetConsoleMode(handle, new_mode)
        )
    except Exception:
        _CONSOLE_STYLING_ENABLED = False


def _ansi(text: str, *styles: str) -> str:
    if not _CONSOLE_STYLING_ENABLED:
        return text
    return "".join(styles) + text + _ANSI_RESET


def _visible_text_length(text: str) -> int:
    return len(_ANSI_ESCAPE_RE.sub("", text))


def _request_style(request_id: str) -> str:
    palette = (_ANSI_CYAN, _ANSI_MAGENTA, _ANSI_BLUE, _ANSI_GREEN, _ANSI_YELLOW)
    return palette[sum(ord(char) for char in request_id) % len(palette)]


def _build_progress_bar(percent: float, width: int = 24) -> str:
    bounded = max(0.0, min(100.0, percent))
    filled = int(round((bounded / 100.0) * width))
    return "[" + ("=" * filled) + ("-" * (width - filled)) + "]"


def _progress_style(percent: float) -> str:
    if percent >= 95.0:
        return _ANSI_GREEN
    if percent >= 60.0:
        return _ANSI_CYAN
    if percent >= 30.0:
        return _ANSI_BLUE
    return _ANSI_YELLOW


def _format_console_body(body: str) -> str:
    progress_match = _PROGRESS_RE.fullmatch(body)
    if progress_match is not None:
        percent_text = progress_match.group("percent")
        percent_value = float(percent_text.replace(",", "."))
        style = _progress_style(percent_value)
        return (
            _ansi(_build_progress_bar(percent_value), _ANSI_BOLD, style)
            + " "
            + _ansi(f"{percent_text}%", _ANSI_BOLD, style)
        )
    lowered = body.lower()
    if "upscale error" in lowered or "failed" in lowered or "traceback" in lowered:
        return _ansi(body, _ANSI_BOLD, _ANSI_RED)
    if body.startswith("Mihon AI companion listening") or lowered.startswith("processed ") or " was closed" in lowered:
        return _ansi(body, _ANSI_BOLD, _ANSI_GREEN)
    if "accepted upscale request" in lowered:
        return _ansi(body, _ANSI_BOLD, _ANSI_CYAN)
    if "force-closing" in lowered or "client disconnected" in lowered or "vanished while closing" in lowered:
        return _ansi(body, _ANSI_BOLD, _ANSI_YELLOW)
    if body.startswith("Mode:") or body.startswith("Binary:") or body.startswith("Model dir:") or body.startswith("Live logs:") or body.startswith("Console logging:"):
        return _ansi(body, _ANSI_WHITE)
    if body.startswith("[") and ("queuec=" in lowered or "fp16-" in lowered or "subgroup=" in lowered or "bug" in lowered):
        return _ansi(body, _ANSI_MAGENTA)
    if "has alpha channel !" in lowered:
        return _ansi(body, _ANSI_YELLOW)
    return body


def _format_console_message(message: str) -> str:
    if not _CONSOLE_STYLING_ENABLED:
        return message
    formatted_lines: list[str] = []
    for line in message.splitlines(keepends=True):
        newline = "\n" if line.endswith("\n") else ""
        line_body = line[:-1] if newline else line
        if not line_body:
            formatted_lines.append(line)
            continue
        timestamp_text: str | None = None
        request_id: str | None = None
        body = line_body
        timestamp_match = _LOG_TIMESTAMP_RE.match(body)
        if timestamp_match is not None:
            timestamp_text = timestamp_match.group(1)
            body = timestamp_match.group(2)
        request_match = _LOG_REQUEST_RE.match(body)
        if request_match is not None:
            request_id = request_match.group(1)
            body = request_match.group(2)
        prefix_parts: list[str] = []
        if timestamp_text is not None:
            prefix_parts.append(_ansi(f"[{timestamp_text}]", _ANSI_DIM))
        if request_id is not None:
            prefix_parts.append(_ansi(f"[{request_id}]", _ANSI_BOLD, _request_style(request_id)))
        prefix = (" ".join(prefix_parts) + " ") if prefix_parts else ""
        formatted_lines.append(prefix + _format_console_body(body) + newline)
    return "".join(formatted_lines)


def _format_inline_progress_message(message: str) -> str | None:
    stripped = message.rstrip("\r\n")
    if not stripped or "\n" in stripped or "\r" in stripped:
        return None
    timestamp_text: str | None = None
    request_id: str | None = None
    body = stripped
    timestamp_match = _LOG_TIMESTAMP_RE.match(body)
    if timestamp_match is not None:
        timestamp_text = timestamp_match.group(1)
        body = timestamp_match.group(2)
    request_match = _LOG_REQUEST_RE.match(body)
    if request_match is not None:
        request_id = request_match.group(1)
        body = request_match.group(2)
    if _PROGRESS_RE.fullmatch(body) is None:
        return None
    prefix_parts: list[str] = []
    if timestamp_text is not None:
        prefix_parts.append(_ansi(f"[{timestamp_text}]", _ANSI_DIM))
    if request_id is not None:
        prefix_parts.append(_ansi(f"[{request_id}]", _ANSI_BOLD, _request_style(request_id)))
    prefix = (" ".join(prefix_parts) + " ") if prefix_parts else ""
    return prefix + _format_console_body(body)


def _write_console_direct(message: str) -> bool:
    if os.name != "nt":
        return False
    try:
        handle = ctypes.windll.kernel32.GetStdHandle(-11)
        if handle in (0, -1):
            return False
        normalized_message = message.replace("\r\n", "\n").replace("\n", "\r\n")
        written = ctypes.c_ulong()
        success = ctypes.windll.kernel32.WriteConsoleW(
            handle,
            normalized_message,
            len(normalized_message),
            ctypes.byref(written),
            None,
        )
        return bool(success)
    except Exception:
        return False


def _write_console_stream(console: Any, message: str) -> None:
    wrote_to_console = _write_console_direct(message)
    if not wrote_to_console:
        try:
            encoding = getattr(console, "encoding", None) or "utf-8"
            os.write(1, message.encode(encoding, errors="replace"))
            wrote_to_console = True
        except OSError:
            pass
    if console is not None and not wrote_to_console:
        console.write(message)
        console.flush()


def _clear_inline_progress(console: Any) -> None:
    global _INLINE_PROGRESS_ACTIVE
    global _INLINE_PROGRESS_VISIBLE_LENGTH
    if not _INLINE_PROGRESS_ACTIVE:
        return
    if _CONSOLE_STYLING_ENABLED:
        _write_console_stream(console, "\r\x1b[2K")
    else:
        _write_console_stream(
            console,
            "\r" + (" " * _INLINE_PROGRESS_VISIBLE_LENGTH) + "\r",
        )
    _INLINE_PROGRESS_ACTIVE = False
    _INLINE_PROGRESS_VISIBLE_LENGTH = 0


def _write_inline_progress(console: Any, message: str) -> None:
    global _INLINE_PROGRESS_ACTIVE
    global _INLINE_PROGRESS_VISIBLE_LENGTH
    visible_length = _visible_text_length(message)
    if _CONSOLE_STYLING_ENABLED:
        payload = "\r\x1b[2K" + message
    else:
        padding = max(0, _INLINE_PROGRESS_VISIBLE_LENGTH - visible_length)
        payload = "\r" + message + (" " * padding)
    _write_console_stream(console, payload)
    _INLINE_PROGRESS_ACTIVE = True
    _INLINE_PROGRESS_VISIBLE_LENGTH = visible_length


def _emit_log_text(message: str, *, console: bool = True, log_file: bool = True) -> None:
    with _LOG_LOCK:
        console_stream = getattr(sys, "__stdout__", None) or sys.stdout
        console_is_tty = getattr(console_stream, "isatty", lambda: False)()
        if console:
            if console_is_tty:
                inline_progress = _format_inline_progress_message(message)
                if inline_progress is not None:
                    _write_inline_progress(console_stream, inline_progress)
                else:
                    _clear_inline_progress(console_stream)
                    _write_console_stream(console_stream, _format_console_message(message))
            else:
                _write_console_stream(console_stream, message)
        elif console_is_tty:
            inline_progress = _format_inline_progress_message(message)
            if inline_progress is None:
                _clear_inline_progress(console_stream)
        if log_file and _LOG_FILE_HANDLE is not None:
            _LOG_FILE_HANDLE.write(message)
            _LOG_FILE_HANDLE.flush()


def _run_hidden_windows_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    kwargs: dict[str, Any] = {
        "stdout": subprocess.PIPE,
        "stderr": subprocess.PIPE,
        "text": True,
        "encoding": locale.getpreferredencoding(False) or "utf-8",
        "errors": "replace",
    }
    if os.name == "nt":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.run(command, **kwargs)


def _find_listening_process_id(port: int) -> int | None:
    if os.name != "nt":
        return None
    command = [
        "powershell",
        "-NoProfile",
        "-Command",
        (
            f"$listenerPid = Get-NetTCPConnection -LocalPort {port} -State Listen "
            "-ErrorAction SilentlyContinue | Select-Object -First 1 "
            "-ExpandProperty OwningProcess; "
            'if ($listenerPid) { Write-Output $listenerPid }'
        ),
    ]
    try:
        result = _run_hidden_windows_command(command)
    except OSError:
        return None
    pid_text = result.stdout.strip()
    return int(pid_text) if pid_text.isdigit() else None


def _show_duplicate_close_failed_dialog(port: int, pid: int, details: str) -> None:
    if os.name != "nt":
        return
    text = (
        f"Couldn't close the old Mihon AI companion on port {port}.\n\n"
        f"Process ID: {pid}\n"
        f"Details: {details}"
    )
    flags = 0x00000000 | 0x00000010 | 0x00040000  # OK + error + topmost
    ctypes.windll.user32.MessageBoxW(None, text, "Mihon AI Companion", flags)


def _wait_for_port_release(port: int, timeout_seconds: float = 5.0) -> bool:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if _find_listening_process_id(port) is None:
            return True
        time.sleep(0.2)
    return False


def _close_existing_instance(port: int, pid: int) -> bool:
    _emit_log_line(
        f"Force-closing previous companion instance on port {port} (pid={pid})"
    )
    try:
        result = _run_hidden_windows_command(["taskkill", "/PID", str(pid), "/T", "/F"])
    except OSError as exc:
        _emit_log_line(f"Failed to close previous companion instance: {exc}")
        _show_duplicate_close_failed_dialog(port, pid, str(exc))
        return False
    if result.returncode != 0:
        if _wait_for_port_release(port, timeout_seconds=1.0):
            _emit_log_line(
                f"Previous companion instance on port {port} vanished while closing"
            )
            return True
        details = result.stdout.strip() or result.stderr.strip() or f"exit code {result.returncode}"
        _emit_log_line(f"Failed to close previous companion instance: {details}")
        _show_duplicate_close_failed_dialog(port, pid, details)
        return False
    if not _wait_for_port_release(port):
        details = "port stayed busy after taskkill"
        _emit_log_line(f"Failed to close previous companion instance: {details}")
        _show_duplicate_close_failed_dialog(port, pid, details)
        return False
    _emit_log_line(f"Previous companion instance on port {port} was closed")
    return True


def _is_address_in_use_error(exc: OSError) -> bool:
    return getattr(exc, "winerror", None) == 10048 or exc.errno == 10048


def _create_server_with_duplicate_resolution(config: Config) -> ReaderAiServer | None:
    current_pids = {os.getpid(), os.getppid()}
    duplicate_pid = _find_listening_process_id(config.port)
    if duplicate_pid is not None and duplicate_pid not in current_pids:
        if not _close_existing_instance(config.port, duplicate_pid):
            return None
    try:
        return ReaderAiServer((config.host, config.port), config)
    except OSError as exc:
        if not _is_address_in_use_error(exc):
            raise
        duplicate_pid = _find_listening_process_id(config.port)
        if duplicate_pid is None or duplicate_pid in current_pids:
            raise
        if not _close_existing_instance(config.port, duplicate_pid):
            return None
        return ReaderAiServer((config.host, config.port), config)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mihon AI remote companion server")
    parser.add_argument("--config", default=DEFAULT_CONFIG_NAME)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--token")
    parser.add_argument("--mode", choices=["subprocess", "mock_copy"])
    parser.add_argument("--binary")
    parser.add_argument("--model-dir")
    parser.add_argument("--model-name")
    parser.add_argument("--output-format")
    parser.add_argument("--scale", type=int)
    parser.add_argument("--tile-size", type=int)
    parser.add_argument("--gpu-id", type=int)
    parser.add_argument("--jobs")
    parser.add_argument("--timeout-seconds", type=int)
    parser.add_argument("--max-workers", type=int)
    parser.add_argument("--batch-size", type=int)
    parser.add_argument("--batch-wait-milliseconds", type=int)
    parser.add_argument("--max-request-megabytes", type=int)
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(line_buffering=True, write_through=True)
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(line_buffering=True, write_through=True)
    _enable_console_styling()
    try:
        log_path = _configure_output_tee()

        args = _parse_args()
        config_path = _resolve_config_path(args.config)
        config = Config.load(
            config_path,
            overrides={
                "host": args.host,
                "port": args.port,
                "token": args.token,
                "mode": args.mode,
                "binary": args.binary,
                "model_dir": args.model_dir,
                "model_name": args.model_name,
                "output_format": args.output_format,
                "scale": args.scale,
                "tile_size": args.tile_size,
                "gpu_id": args.gpu_id,
                "jobs": args.jobs,
                "timeout_seconds": args.timeout_seconds,
                "max_workers": args.max_workers,
                "batch_size": args.batch_size,
                "batch_wait_milliseconds": args.batch_wait_milliseconds,
                "max_request_megabytes": args.max_request_megabytes,
            },
        )

        server = _create_server_with_duplicate_resolution(config)
        if server is None:
            return 1
        _emit_log_line(f"Mihon AI companion listening on http://{config.host}:{config.port}")
        _emit_log_line(f"Mode: {config.mode}")
        _emit_log_line(f"Binary: {config.binary}")
        _emit_log_line(f"Model dir: {config.model_dir}")
        _emit_log_line(f"Live logs: enabled -> {log_path}")
        _emit_log_line("Console logging: win32 direct + fd fallback")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            _emit_log_text("\nShutting down...\n")
        finally:
            server.server_close()
        return 0
    finally:
        if _LOG_FILE_HANDLE is not None:
            _LOG_FILE_HANDLE.close()


if __name__ == "__main__":
    raise SystemExit(main())
