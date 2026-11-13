"""GPU fault containment using fake native children, never a real GPU runtime."""
from __future__ import annotations

import json
import queue
import subprocess
import threading
import unittest
from io import BytesIO, StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from PIL import Image

import reader_ai_companion as companion


def png() -> bytes:
    with Image.new("RGB", (8, 12), "white") as image, BytesIO() as output:
        image.save(output, "PNG")
        return output.getvalue()


class LivePipe:
    """A pipe that cannot end until its fake child exits or is killed."""

    def __init__(self):
        self.lines = queue.Queue()
        self.started = threading.Event()
        self.closed = False

    def __iter__(self):
        self.started.set()
        while (line := self.lines.get()) is not None:
            yield line

    def emit(self, line):
        self.lines.put(line + "\n")

    def end(self):
        self.lines.put(None)

    def close(self):
        self.closed = True
        self.end()


class FakeNativeProcess:
    """An observable child lifecycle; poll alone does not reap it."""

    pid = 424242

    def __init__(self, *, returncode=None, lines=(), on_poll=None, wait_gate=None):
        self.returncode = returncode
        self.stdout = LivePipe() if returncode is None else StringIO("".join(line + "\n" for line in lines))
        self.killed = threading.Event()
        self.wait_entered = threading.Event()
        self.reaped = threading.Event()
        self.on_poll = on_poll
        self.wait_gate = wait_gate
        self.calls = []

    def poll(self):
        if self.on_poll is not None:
            self.on_poll()
        return self.returncode

    def kill(self):
        self.calls.append("kill")
        self.killed.set()
        self.returncode = -9
        if isinstance(self.stdout, LivePipe):
            self.stdout.end()

    def wait(self, timeout=None):
        self.calls.append("wait")
        self.wait_entered.set()
        if self.wait_gate is not None and not self.wait_gate.wait(2):
            raise subprocess.TimeoutExpired("fixture", timeout)
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fixture", timeout)
        self.reaped.set()
        return self.returncode


class ObservableSlot:
    def __init__(self, process):
        self.slot = threading.Semaphore(1)
        self.waiting = threading.Event()
        self.released = threading.Event()
        self.release_before_reap = False
        self.process = process
        self._acquires = 0
        self._lock = threading.Lock()

    def acquire(self, *args, **kwargs):
        with self._lock:
            self._acquires += 1
            if self._acquires > 1:
                self.waiting.set()
        return self.slot.acquire(*args, **kwargs)

    def release(self):
        if not self.process.reaped.is_set():
            self.release_before_reap = True
        self.released.set()
        self.slot.release()


class GpuSafetyTests(unittest.TestCase):
    def setUp(self):
        logs = patch.object(companion, "_emit_log_line")
        self.logs = logs.start()
        self.addCleanup(logs.stop)
        self.server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config())
        self.addCleanup(self.server.server_close)
        self.opener = build_opener(ProxyHandler({}))

    def patch(self, target, name, **kwargs):
        patcher = patch.object(target, name, **kwargs)
        mocked = patcher.start()
        self.addCleanup(patcher.stop)
        return mocked

    def native(self, *, timeout=10, cancel_event=None, server=None, command=None):
        return companion._run_logged_subprocess(
            request_id="req-fault-fixture", command=command or ["unused-native-runtime"], cwd=Path.cwd(),
            timeout=timeout, client_id="phone", cancel_event=cancel_event,
            process_registry=server or self.server,
        )

    def start_native(self, **kwargs):
        outcomes = []
        completed = threading.Event()

        def run():
            try:
                outcomes.append(self.native(**kwargs))
            except Exception as exc:
                outcomes.append(exc)
            finally:
                completed.set()

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        return outcomes, completed

    def serve(self):
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(self.server.shutdown)
        return f"http://127.0.0.1:{self.server.server_port}"

    def test_default_ncnn_configuration_bounds_tile_and_compute_workers(self):
        config = companion.Config()
        self.assertEqual(config.tile_size, 128)
        self.assertEqual(config.jobs.split(":")[1], "1")

    def test_existing_configuration_cannot_restore_automatic_or_oversized_tiles(self):
        with TemporaryDirectory() as temporary:
            config_path = Path(temporary) / "reader_ai_server.json"
            for old_tile in (0, 2048):
                with self.subTest(tile=old_tile):
                    config_path.write_text(json.dumps({"tile_size": old_tile, "jobs": "2:8:8"}), encoding="utf-8")
                    config = companion.Config.load(config_path, overrides={})
                    self.assertEqual(config.tile_size, 128 if old_tile == 0 else 256)
                    self.assertEqual(config.jobs.split(":")[1], "1")

    def test_actual_native_launch_enforces_limits_even_for_an_unsanitized_command(self):
        process = FakeNativeProcess(returncode=0)
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        self.native(command=["unused", "-t", "2048", "-j", "2:8:8"])
        command = popen.call_args.args[0]
        self.assertGreater(int(command[command.index("-t") + 1]), 0)
        self.assertLessEqual(int(command[command.index("-t") + 1]), 256)
        self.assertEqual(command[command.index("-j") + 1].split(":")[1], "1")

    def test_terminal_zero_exit_with_device_lost_output_is_still_a_gpu_fault(self):
        process = FakeNativeProcess(returncode=0, lines=["vkQueueSubmit failed -4"])
        self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertFalse(self.server.gpu_status()["available"])
        self.assertTrue(process.reaped.is_set())

    def test_unexpected_native_crash_latches_fault_without_relying_on_stderr(self):
        process = FakeNativeProcess(returncode=-1073740791, lines=["[0 NVIDIA GeForce RTX 5080] queueC=2"])
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertTrue(process.reaped.is_set())
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertEqual(popen.call_count, 1)

    def test_recognized_encoder_error_can_retry_without_faulting_the_gpu(self):
        process = FakeNativeProcess(returncode=1, lines=["encode image failed"])
        self.patch(companion.subprocess, "Popen", return_value=process)
        result = self.native()
        self.assertEqual(result.returncode, 1)
        self.assertTrue(process.reaped.is_set())
        self.assertTrue(self.server.gpu_status()["available"])

    def test_driver_crash_is_not_excused_by_an_earlier_encoder_message(self):
        # Windows can report exception statuses as either signed or unsigned.
        for code in (-1073740791, 3221226505):
            with self.subTest(returncode=code):
                server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config())
                self.addCleanup(server.server_close)
                process = FakeNativeProcess(returncode=code, lines=["encode image failed", "NVIDIA runtime stopped"])
                with patch.object(companion.subprocess, "Popen", return_value=process):
                    with self.assertRaises(companion.GPUUnavailableError):
                        self.native(server=server)
                self.assertFalse(server.gpu_status()["available"])

    def test_concurrent_reader_cancellation_cannot_hide_a_windows_driver_crash(self):
        for code in (-1073740791, 3221226505, 71):
            with self.subTest(returncode=code):
                server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config())
                self.addCleanup(server.server_close)
                cancel = threading.Event()
                process = FakeNativeProcess(returncode=code, on_poll=cancel.set)
                with patch.object(companion.subprocess, "Popen", return_value=process):
                    with self.assertRaises(companion.GPUUnavailableError):
                        self.native(cancel_event=cancel, server=server)
                self.assertTrue(process.reaped.is_set())
                self.assertFalse(server.gpu_status()["available"])

    def test_live_fatal_output_stops_the_child_without_waiting_for_its_timeout(self):
        process = FakeNativeProcess()
        self.addCleanup(process.kill)
        self.patch(companion.subprocess, "Popen", return_value=process)
        outcomes, completed = self.start_native(timeout=1800)
        self.assertTrue(process.stdout.started.wait(2))
        process.stdout.emit("vkWaitForFences failed -4")
        self.assertTrue(completed.wait(2), "GPU fault remained blocked on the chapter timeout")
        self.assertEqual(len(outcomes), 1)
        self.assertIsInstance(outcomes[0], companion.GPUUnavailableError)
        self.assertTrue(process.killed.is_set())
        self.assertTrue(process.reaped.is_set())

    def test_live_native_runtime_fault_marker_stops_a_still_running_child(self):
        process = FakeNativeProcess()
        self.addCleanup(process.kill)
        self.patch(companion.subprocess, "Popen", return_value=process)
        outcomes, completed = self.start_native(timeout=1800)
        self.assertTrue(process.stdout.started.wait(2))
        process.stdout.emit("MIHON_GPU_FAULT: ncnn VkCompute submit failed")
        self.assertTrue(completed.wait(2), "Native fail-stop marker did not stop the child promptly")
        self.assertIsInstance(outcomes[0], companion.GPUUnavailableError)
        self.assertTrue(process.killed.is_set())
        self.assertTrue(process.reaped.is_set())

    def test_native_runtime_fault_exit_status_stops_the_session_without_driver_text(self):
        process = FakeNativeProcess(returncode=71)
        self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertTrue(process.reaped.is_set())
        self.assertFalse(self.server.gpu_status()["available"])

    def test_faulting_child_is_reaped_before_the_gpu_slot_is_released(self):
        wait_gate = threading.Event()
        self.addCleanup(wait_gate.set)
        process = FakeNativeProcess(wait_gate=wait_gate)
        self.addCleanup(process.kill)
        slot = ObservableSlot(process)
        self.server._subprocess_slot = slot
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        first, first_done = self.start_native()
        self.assertTrue(process.stdout.started.wait(2))
        second, second_done = self.start_native()
        self.assertTrue(slot.waiting.wait(2))
        process.stdout.emit("VK_ERROR_DEVICE_LOST")
        self.assertTrue(process.wait_entered.wait(2))
        self.assertFalse(slot.released.is_set(), "GPU lease was returned before child reaping completed")
        self.assertEqual(popen.call_count, 1)
        wait_gate.set()
        self.assertTrue(first_done.wait(2))
        self.assertTrue(second_done.wait(2))
        self.assertIsInstance(first[0], companion.GPUUnavailableError)
        self.assertIsInstance(second[0], companion.GPUUnavailableError)
        self.assertEqual(popen.call_count, 1, "A queued GPU process launched after the first fatal fault")
        self.assertFalse(slot.release_before_reap)

    def test_timeout_kills_reaps_and_blocks_new_native_work(self):
        process = FakeNativeProcess()
        self.addCleanup(process.kill)
        self.server._subprocess_slot = slot = ObservableSlot(process)
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native(timeout=0)
        self.assertTrue(process.killed.is_set())
        self.assertTrue(process.reaped.is_set())
        self.assertFalse(slot.release_before_reap)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertEqual(popen.call_count, 1)

    def test_unreapable_child_keeps_the_gpu_lease_until_the_background_reaper_finishes(self):
        allow_exit = threading.Event()
        self.addCleanup(allow_exit.set)

        class StuckNativeProcess(FakeNativeProcess):
            def kill(self):
                self.calls.append("kill")
                self.killed.set()
                self.stdout.end()

            def wait(self, timeout=None):
                self.calls.append("wait")
                self.wait_entered.set()
                if timeout is not None or not allow_exit.wait(2):
                    raise subprocess.TimeoutExpired("fixture", timeout)
                self.returncode = -9
                self.reaped.set()
                return self.returncode

        process = StuckNativeProcess()
        self.server._subprocess_slot = slot = ObservableSlot(process)
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native(timeout=0)
        self.assertFalse(process.reaped.is_set())
        self.assertFalse(slot.released.is_set())
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertEqual(popen.call_count, 1)
        allow_exit.set()
        self.assertTrue(slot.released.wait(2))
        self.assertFalse(slot.release_before_reap)

    def test_all_native_pipelines_propagate_a_fatal_fault_without_retrying(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "realesrgan-ncnn-vulkan.exe"
            binary.touch()
            models = root / "models"
            models.mkdir()
            config = companion.Config(binary=str(binary), model_dir=str(models))
            for route in ("single", "batch", "chapter", "chunk"):
                with self.subTest(route=route):
                    server = companion.ReaderAiServer(("127.0.0.1", 0), config)
                    self.addCleanup(server.server_close)
                    workspace = root / route
                    workspace.mkdir()
                    request = companion.PendingUpscaleRequest(
                        request_id="req-native-route", body=png(), input_format="png", requested_output_format="png",
                        model_name=companion.FAST_MODEL_NAME, native_scale=2, preferred_output_format="png",
                        batch_size=4, is_chunked=False, target_width=64,
                    )
                    process = FakeNativeProcess(returncode=0, lines=["vkQueueSubmit failed -4"])
                    with patch.object(companion.subprocess, "Popen", return_value=process) as popen:
                        with self.assertRaises(companion.GPUUnavailableError):
                            if route == "single":
                                companion._process_single_request_in_workspace(config, workspace, request, server)
                            elif route == "batch":
                                other = companion.PendingUpscaleRequest(
                                    request_id="req-native-route-2", body=png(), input_format="png", requested_output_format="png",
                                    model_name=companion.FAST_MODEL_NAME, native_scale=2, preferred_output_format="png",
                                    batch_size=4, is_chunked=False, target_width=64,
                                )
                                companion._run_subprocess_batch(config, workspace, [request, other], server)
                            elif route == "chunk":
                                companion._run_chunked_subprocess(
                                    config=config, request_id=request.request_id, workspace=workspace, body=request.body,
                                    requested_output_format="png", model_name=request.model_name,
                                    native_scale=2, target_scale=2, target_width=64, process_registry=server,
                                )
                            else:
                                source = workspace / "0000.png"
                                source.write_bytes(request.body)
                                output = workspace / "out"
                                output.mkdir()
                                page = companion.PreparedChapterPage(0, source, "png")
                                job = companion.ChapterUpscaleJob(
                                    job_id="native-route", request_id=request.request_id, created_at=companion.time.time(),
                                    model_name=request.model_name, native_scale=2, requested_output_format="png",
                                    batch_output_format="png", workspace=workspace, input_dir=workspace, output_dir=output,
                                    pages={0: page}, target_width=64,
                                )
                                server._run_chapter_batch(job, [page], workspace, output)
                        self.assertEqual(popen.call_count, 1, f"{route} retried after a fatal GPU fault")
                        self.assertFalse(server.gpu_status()["available"])

    def test_expected_reader_cancellation_does_not_poison_the_gpu_session(self):
        cancel = threading.Event()
        canceled = FakeNativeProcess(on_poll=cancel.set)
        healthy = FakeNativeProcess(returncode=0, lines=["100.00%"])
        self.server._subprocess_slot = slot = ObservableSlot(canceled)
        popen = self.patch(companion.subprocess, "Popen", side_effect=[canceled, healthy])
        with self.assertRaisesRegex(RuntimeError, "(?i)cancel"):
            self.native(cancel_event=cancel)
        self.assertTrue(canceled.killed.is_set())
        self.assertTrue(canceled.reaped.is_set())
        self.assertFalse(slot.release_before_reap)
        self.assertTrue(self.server.gpu_status()["available"])
        result = self.native()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(popen.call_count, 2)

    def test_fault_latch_does_not_reset_when_a_new_request_or_client_arrives(self):
        self.server.latch_gpu_fault("native runtime fault")
        first = self.server.gpu_status()
        self.server.sync_client_scope("another-phone", 123)
        self.server.abort_client_work("another-phone")
        popen = self.patch(companion.subprocess, "Popen")
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertEqual(self.server.gpu_status(), first)
        popen.assert_not_called()
        other = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config())
        self.addCleanup(other.server_close)
        self.assertTrue(other.gpu_status()["available"], "Only a new server session may clear a GPU fault")

    def test_fatal_output_flood_is_not_copied_unbounded_into_the_log(self):
        process = FakeNativeProcess(returncode=0, lines=["vkQueueSubmit failed -4"] * 10000)
        self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertLess(self.logs.call_count, 100, "Fatal Vulkan spam was copied into the persistent log")

    def test_pipe_that_never_finishes_after_child_exit_is_a_monitor_fault(self):
        # A descendant retaining stdout must not allow a late fatal line to
        # arrive only after another GPU task has started.
        process = FakeNativeProcess(returncode=0)
        process.stdout = LivePipe()
        self.addCleanup(process.stdout.close)
        popen = self.patch(companion.subprocess, "Popen", return_value=process)
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertTrue(process.reaped.is_set())
        self.assertFalse(self.server.gpu_status()["available"])
        with self.assertRaises(companion.GPUUnavailableError):
            self.native()
        self.assertEqual(popen.call_count, 1)

    def test_only_native_child_disables_implicit_layers_and_preserves_driver_selection(self):
        original = {
            "PATH": "fixture-path", "HOME": "fixture-home",
            "VK_LOADER_LAYERS_DISABLE": "VK_LAYER_CUSTOM_other,~explicit~",
            "VK_LOADER_LAYERS_ENABLE": "VK_LAYER_VALVE_steam_overlay",
            "VK_LOADER_LAYERS_ALLOW": "*",
            "VK_INSTANCE_LAYERS": "VK_LAYER_VALVE_steam_overlay",
            "ENABLE_VK_LAYER_VALVE_steam_overlay_1": "1",
            "VK_DRIVER_FILES": "fixture-selected-driver.json",
            "VK_LOADER_DRIVERS_SELECT": "fixture-discrete-driver*",
        }
        process = FakeNativeProcess(returncode=0)
        with patch.dict(companion.os.environ, original, clear=True):
            before = dict(companion.os.environ)
            with patch.object(companion.subprocess, "Popen", return_value=process) as popen:
                self.native()
            self.assertEqual(dict(companion.os.environ), before, "Parent/game Vulkan environment was changed")
        child = popen.call_args.kwargs["env"]
        disabled = child["VK_LOADER_LAYERS_DISABLE"].split(",")
        self.assertIn("VK_LAYER_CUSTOM_other", disabled)
        self.assertIn("~explicit~", disabled)
        self.assertEqual(disabled.count("~implicit~"), 1)
        for name in ("VK_LOADER_LAYERS_ENABLE", "VK_LOADER_LAYERS_ALLOW", "VK_INSTANCE_LAYERS",
                     "ENABLE_VK_LAYER_VALVE_steam_overlay_1"):
            self.assertNotIn(name, child)
        self.assertEqual(child["DISABLE_VK_LAYER_VALVE_steam_overlay_1"], "1")
        for name in ("PATH", "HOME", "VK_DRIVER_FILES", "VK_LOADER_DRIVERS_SELECT"):
            self.assertEqual(child[name], original[name])

    def test_already_disabled_implicit_layers_are_not_appended_again(self):
        process = FakeNativeProcess(returncode=0)
        with patch.dict(companion.os.environ, {"VK_LOADER_LAYERS_DISABLE": "~implicit~,VK_LAYER_CUSTOM_other"}):
            with patch.object(companion.subprocess, "Popen", return_value=process) as popen:
                self.native()
        disabled = popen.call_args.kwargs["env"]["VK_LOADER_LAYERS_DISABLE"].split(",")
        self.assertEqual(disabled.count("~implicit~"), 1)
        self.assertIn("VK_LAYER_CUSTOM_other", disabled)

    def test_runtime_metadata_exposes_only_finite_provenance_fields(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "realesrgan-ncnn-vulkan.exe"
            manifest = {
                "format": "mihon-native-runtime", "version": 1,
                "runtime_version": "ncnn-20261020-mihon1",
                "pins": {"ncnn": "A" * 40, "webp": "not-a-commit", "secret": "B" * 40},
                "files": {binary.name: "C" * 64, "private-token": "DO_NOT_EXPOSE"},
                "source_url": "https://DO_NOT_EXPOSE.invalid/?token=DO_NOT_EXPOSE",
            }
            (root / "native-runtime-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            metadata = companion._gpu_runtime_metadata(binary)
        self.assertEqual(metadata, {
            "runtime_version": "ncnn-20261020-mihon1", "pins": {"ncnn": "A" * 40},
            "manifest_binary_sha256": "c" * 64,
        })
        self.assertNotIn("DO_NOT_EXPOSE", json.dumps(metadata))

    def test_runtime_metadata_rejects_invalid_versions_types_and_oversized_documents(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "realesrgan-ncnn-vulkan.exe"
            manifest_path = root / "native-runtime-manifest.json"
            base = {"format": "mihon-native-runtime", "version": 1, "runtime_version": "valid-version"}
            invalid = (
                {**base, "version": True}, {**base, "version": 2},
                {**base, "runtime_version": "https://DO_NOT_EXPOSE.invalid/path"},
                {**base, "runtime_version": "x" * 81},
                {**base, "untrusted": "x" * 65537}, [], None,
            )
            for document in invalid:
                with self.subTest(document_type=type(document).__name__):
                    manifest_path.write_text(json.dumps(document), encoding="utf-8")
                    self.assertEqual(companion._gpu_runtime_metadata(binary), {"runtime_version": "unversioned"})

    def test_missing_or_malformed_runtime_manifest_does_not_break_health_metadata(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "realesrgan-ncnn-vulkan.exe"
            self.assertEqual(companion._gpu_runtime_metadata(binary), {"runtime_version": "unversioned"})
            (root / "native-runtime-manifest.json").write_bytes(b"not-json\xff")
            self.assertEqual(companion._gpu_runtime_metadata(binary), {"runtime_version": "unversioned"})

    def test_health_stays_reachable_and_reports_that_gpu_work_is_stopped(self):
        self.server.latch_gpu_fault("native runtime fault")
        base = self.serve()
        with self.opener.open(f"{base}/health", timeout=2) as response:
            self.assertEqual(response.status, 200)
            payload = json.load(response)
        self.assertTrue(payload["ok"])
        self.assertFalse(payload["gpu_available"])
        self.assertEqual(payload["gpu_state"], self.server.gpu_status()["state"])
        self.assertTrue(payload["gpu_error"])

    def test_faulted_server_rejects_all_new_upscale_routes_before_creating_jobs(self):
        self.server.latch_gpu_fault("native runtime fault")
        base = self.serve()
        popen = self.patch(companion.subprocess, "Popen")
        download = self.patch(companion._DIRECT_PAGE_OPENER, "open")
        for route, body in (
            ("/api/upscale", png()),
            ("/api/upscale-chapter", b"not-read-as-a-zip"),
            ("/api/upscale-chapter-direct", json.dumps({"pages": [
                {"page_index": 0, "url": "https://fixture.invalid/page.png"}
            ]}).encode()),
        ):
            with self.subTest(route=route):
                request = Request(base + route, data=body)
                with self.assertRaises(HTTPError) as error:
                    self.opener.open(request, timeout=2)
                with error.exception as response:
                    self.assertEqual(response.code, 503)
                    self.assertIn("restart", response.read().decode().lower())
                    self.assertEqual(response.headers.get("Connection"), "close")
        self.assertFalse(self.server._chapter_jobs)
        popen.assert_not_called()
        download.assert_not_called()

    def test_already_finished_pages_stay_readable_after_a_later_gpu_fault(self):
        image = png()
        directory = self.server._chapter_jobs_root / "ready-fixture"
        directory.mkdir()
        output = directory / "0000.png"
        output.write_bytes(image)
        job = companion.ChapterUpscaleJob(
            job_id="ready-fixture", request_id="req-ready", created_at=companion.time.time(),
            model_name=companion.FAST_MODEL_NAME, native_scale=2,
            requested_output_format="png", batch_output_format="png",
            workspace=directory, input_dir=directory, output_dir=directory,
            pages={0: companion.PreparedChapterPage(0, directory / "0000.png", "png")},
        )
        job.delivery_paths[0] = output
        job.completed.set()
        self.server._chapter_jobs[job.job_id] = job
        self.server.latch_gpu_fault("a later runtime fault")
        base = self.serve()
        with self.opener.open(f"{base}/api/upscale-chapter/{job.job_id}/page/0", timeout=2) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read(), image)
        with self.opener.open(f"{base}/api/upscale-chapter/{job.job_id}/status", timeout=2) as response:
            status = json.load(response)
        self.assertEqual(status["state"], "completed")
        self.assertEqual(status["ready_indices"], [0])


if __name__ == "__main__":
    unittest.main()
