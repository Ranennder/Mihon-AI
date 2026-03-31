#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
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
JPEG_MAX_DIMENSION = 65535
JPEG_OUTPUT_FORMATS = {"jpg", "jpeg"}
PNG_OUTPUT_FORMAT = "png"
INTERNAL_CHUNK_FORMAT = "png"
JPEG_QUALITY = 95
MAX_SINGLE_OUTPUT_DIMENSION = 65535
MAX_CHUNK_OUTPUT_DIMENSION = 16384
CHUNK_OVERLAP_INPUT_PIXELS = 32

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
    max_workers: int = 2
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
        config.binary = str(_resolve_runtime_binary(config.binary, base_dir))
        config.model_dir = str(_resolve_model_dir(config.model_dir, base_dir))
        return config


@dataclass(frozen=True)
class ProcessedImage:
    bytes: bytes
    output_format: str


@dataclass(frozen=True)
class ImageSize:
    width: int
    height: int


class ReaderAiServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, server_address: tuple[str, int], config: Config):
        super().__init__(server_address, ReaderAiRequestHandler)
        self.config = config
        self.processing_slots = threading.Semaphore(config.max_workers)

    def process_image(self, body: bytes, input_format: str, output_format: str, model_name: str) -> ProcessedImage:
        with self.processing_slots:
            if self.config.mode == "mock_copy":
                return ProcessedImage(body, output_format)

            native_scale = _resolve_model_scale(
                model_name=model_name,
                default_scale=self.config.scale,
            )
            target_scale = max(1, int(self.config.scale))

            if _requires_chunked_upscale(
                body=body,
                native_scale=native_scale,
                target_scale=target_scale,
            ):
                return _run_chunked_subprocess(
                    config=self.config,
                    body=body,
                    requested_output_format=output_format,
                    model_name=model_name,
                    native_scale=native_scale,
                    target_scale=target_scale,
                )

            return _run_subprocess(
                config=self.config,
                body=body,
                input_format=input_format,
                requested_output_format=output_format,
                model_name=model_name,
                scale=native_scale,
            )

    def handle_error(self, request: Any, client_address: tuple[str, int]) -> None:
        _, exc, _ = sys.exc_info()
        if isinstance(exc, ConnectionResetError):
            sys.stdout.write(f"[{time.strftime('%d/%b/%Y %H:%M:%S')}] Client disconnected: {client_address[0]}:{client_address[1]}\n")
            sys.stdout.flush()
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

        self.log_message(
            "Accepted upscale request: %d bytes input=%s output=%s model=%s from %s",
            body_size,
            input_format,
            output_format,
            model_name,
            self.client_address[0],
        )

        started_at = time.perf_counter()
        body = self.rfile.read(body_size)
        try:
            processed_image = self.server.process_image(body, input_format, output_format, model_name)
        except subprocess.TimeoutExpired as exc:
            self.log_message("Upscale timeout after %s seconds", exc.timeout)
            self._send_text(HTTPStatus.GATEWAY_TIMEOUT, f"Upscale process timed out after {exc.timeout} seconds")
            return
        except Exception as exc:  # noqa: BLE001
            self.log_message("Upscale error: %s", exc)
            self._send_text(HTTPStatus.BAD_GATEWAY, str(exc))
            return

        elapsed = time.perf_counter() - started_at
        self.log_message(
            "Processed %d bytes into %d bytes in %.2fs via %s (%s -> %s)",
            len(body),
            len(processed_image.bytes),
            elapsed,
            self.server.config.mode,
            model_name,
            processed_image.output_format,
        )
        self._send_binary(HTTPStatus.OK, processed_image.bytes, processed_image.output_format)

    def log_message(self, format: str, *args: Any) -> None:
        sys.stdout.write("[%s] %s\n" % (self.log_date_time_string(), format % args))
        sys.stdout.flush()

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
    body: bytes,
    input_format: str,
    requested_output_format: str,
    model_name: str,
    scale: int,
) -> ProcessedImage:
    binary = Path(config.binary)
    model_dir = Path(config.model_dir)

    if not binary.is_file():
        raise RuntimeError(f"Upscale binary not found: {binary}")
    if not model_dir.is_dir():
        raise RuntimeError(f"Model directory not found: {model_dir}")

    preferred_output_format = _select_output_format(
        body=body,
        requested_output_format=requested_output_format,
        scale=scale,
    )

    try:
        return _run_subprocess_once(
            config=config,
            binary=binary,
            model_dir=model_dir,
            body=body,
            input_format=input_format,
            output_format=preferred_output_format,
            model_name=model_name,
            scale=scale,
        )
    except RuntimeError as exc:
        if (
            preferred_output_format in JPEG_OUTPUT_FORMATS
            and requested_output_format in JPEG_OUTPUT_FORMATS
            and _should_retry_with_png(str(exc))
        ):
            return _run_subprocess_once(
                config=config,
                binary=binary,
                model_dir=model_dir,
                body=body,
                input_format=input_format,
                output_format=PNG_OUTPUT_FORMAT,
                model_name=model_name,
                scale=scale,
            )
        raise


def _run_subprocess_once(
    config: Config,
    binary: Path,
    model_dir: Path,
    body: bytes,
    input_format: str,
    output_format: str,
    model_name: str,
    scale: int,
) -> ProcessedImage:
    with tempfile.TemporaryDirectory(prefix="mihon-ai-") as temp_dir:
        temp_root = Path(temp_dir)
        input_path = temp_root / f"input.{_sanitize_extension(input_format)}"
        output_path = temp_root / f"output.{_sanitize_extension(output_format)}"
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

        completed = subprocess.run(
            command,
            cwd=str(binary.parent),
            capture_output=True,
            timeout=config.timeout_seconds,
            check=False,
        )
        details = _subprocess_details(completed)

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


def _subprocess_details(completed: subprocess.CompletedProcess[bytes]) -> str:
    stderr = completed.stderr.decode("utf-8", errors="replace").strip()
    stdout = completed.stdout.decode("utf-8", errors="replace").strip()
    return stderr or stdout or f"exit code {completed.returncode}"


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
    parser.add_argument("--max-request-megabytes", type=int)
    return parser.parse_args()


def main() -> int:
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
            "max_request_megabytes": args.max_request_megabytes,
        },
    )

    server = ReaderAiServer((config.host, config.port), config)
    print(f"Mihon AI companion listening on http://{config.host}:{config.port}", flush=True)
    print(f"Mode: {config.mode}", flush=True)
    print(f"Binary: {config.binary}", flush=True)
    print(f"Model dir: {config.model_dir}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
