"""Real HTTP/ZIP/ready-page integration with a synthetic directory GPU process."""
from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
import zipfile
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from PIL import Image

import reader_ai_companion as companion


class UploadedChapterPipelineTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        binary = root / "realesrgan-ncnn-vulkan.exe"
        binary.touch()
        models = root / "models"
        models.mkdir()
        self.server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config(
            mode="subprocess", binary=str(binary), model_dir=str(models), token="fixture-token", max_workers=1,
        ))
        self.addCleanup(self.server.server_close)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.opener = build_opener(ProxyHandler({}))
        self.logs = self.patch(companion, "_emit_log_line")
        with Image.new("RGB", (4, 3), "white") as image:
            image.putpixel((1, 1), (20, 90, 180))
            original = BytesIO()
            image.save(original, "PNG")
            self.original = original.getvalue()
            enhanced = BytesIO()
            with image.resize((16, 12), Image.Resampling.NEAREST) as enlarged:
                enlarged.save(enhanced, "PNG")
            self.enhanced = enhanced.getvalue()

    def patch(self, owner, name, **kwargs):
        patcher = patch.object(owner, name, **kwargs)
        result = patcher.start()
        self.addCleanup(patcher.stop)
        return result

    def archive(self, count=3):
        data = BytesIO()
        with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_STORED) as archive:
            for index in range(count):
                archive.writestr(f"{index:04d}.png", self.original)
        return data.getvalue()

    def request(self, path, body=None, priority="2", scope=1):
        headers = {
            "X-Reader-AI-Token": "fixture-token", "X-Reader-AI-Client-Id": "phone",
            "X-Reader-AI-Work-Scope": str(scope), "X-Reader-AI-Model-Name": "realesrgan-x4plus-anime",
            "X-Reader-AI-Output-Format": "png", "X-Reader-AI-Archive-Format": "zip",
            "Content-Type": "application/zip",
        }
        if priority is not None:
            headers["X-Reader-AI-Priority-Count"] = priority
        return self.opener.open(Request(self.base + path, data=body, headers=headers), timeout=3)

    def post_archive(self, priority="2", body=None, scope=1):
        with self.request("/api/upscale-chapter", self.archive() if body is None else body, priority, scope) as response:
            self.assertEqual(response.status, 202)
            return json.load(response)

    def status(self, job_id):
        with self.request(f"/api/upscale-chapter/{job_id}/status") as response:
            return json.load(response)

    def wait_ready(self, job_id, count):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status = self.status(job_id)
            if status["ready_count"] >= count:
                return status
            time.sleep(0.01)
        self.fail("Validated uploaded outputs did not become ready")

    def gpu_paths(self, command):
        input_dir = Path(command[command.index("-i") + 1])
        output_dir = Path(command[command.index("-o") + 1])
        self.assertTrue(input_dir.is_dir())
        self.assertTrue(output_dir.is_dir())
        self.assertEqual(sorted(path.name for path in input_dir.iterdir()), ["0000.png", "0001.png", "0002.png"])
        self.assertEqual(command[command.index("-s") + 1], "4")
        self.assertEqual(command[command.index("-n") + 1], "realesrgan-x4plus-anime")
        return input_dir, output_dir

    def test_upload_runs_three_pages_in_one_process_and_publishes_before_process_exit(self):
        first_written = threading.Event()
        finish_gpu = threading.Event()
        self.addCleanup(finish_gpu.set)

        def gpu(**kwargs):
            _, output_dir = self.gpu_paths(kwargs["command"])
            job = next(iter(self.server._chapter_jobs.values()))
            self.assertNotEqual(output_dir, job.output_dir)
            (output_dir / "0000.png").write_bytes(self.enhanced)
            first_written.set()
            self.assertTrue(finish_gpu.wait(3))
            for index in (1, 2):
                (output_dir / f"{index:04d}.png").write_bytes(self.enhanced)
            return companion.SubprocessRunResult(0, "fixture directory completed")

        process = self.patch(companion, "_run_logged_subprocess", side_effect=gpu)
        fallback = self.patch(companion, "_process_single_request_in_workspace")
        accepted = self.post_archive()
        self.assertEqual(accepted["page_count"], 3)
        self.assertEqual(accepted["priority_count"], 2)
        self.assertTrue(first_written.wait(2))
        job = self.server._chapter_jobs[accepted["job_id"]]
        early = self.wait_ready(job.job_id, 1)
        self.assertEqual(early["state"], "running")
        self.assertEqual(early["ready_indices"], [0])
        self.assertEqual(early["priority_ready_count"], 1)
        self.assertFalse(early["priority_ready"])
        self.assertFalse(job.completed.is_set())
        with self.request(f"/api/upscale-chapter/{job.job_id}/page/0") as page:
            self.assertEqual(page.status, 200)
            self.assertEqual(page.read(), self.enhanced)
        process.assert_called_once()
        fallback.assert_not_called()
        finish_gpu.set()
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        complete = self.wait_ready(job.job_id, 3)
        self.assertEqual(complete["state"], "completed")
        self.assertTrue(complete["priority_ready"])
        self.assertTrue(any("3 pages, input=directory, priority=2" in str(call) for call in self.logs.call_args_list))

    def test_missing_batch_output_retries_only_that_page_and_logs_single_fallback(self):
        def gpu(**kwargs):
            _, output_dir = self.gpu_paths(kwargs["command"])
            for index in (0, 1):
                (output_dir / f"{index:04d}.png").write_bytes(self.enhanced)
            return companion.SubprocessRunResult(0, "fixture omitted page two")

        process = self.patch(companion, "_run_logged_subprocess", side_effect=gpu)
        fallback = self.patch(companion, "_process_single_request_in_workspace",
                              return_value=companion.ProcessedImage(self.enhanced, "png"))
        accepted = self.post_archive()
        job = self.server._chapter_jobs[accepted["job_id"]]
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        process.assert_called_once()
        fallback.assert_called_once()
        self.assertIs(fallback.call_args.kwargs["request"].cancel_event, job.cancel_event)
        self.assertIn("page-2/fallback", fallback.call_args.kwargs["request"].request_id)
        self.assertEqual(self.status(job.job_id)["ready_indices"], [0, 1, 2])
        for index in range(3):
            with self.request(f"/api/upscale-chapter/{job.job_id}/page/{index}") as page:
                self.assertEqual(page.read(), self.enhanced)
        self.assertTrue(any("1 missing or invalid outputs; retrying" in str(call) for call in self.logs.call_args_list))
        self.assertTrue(any("fallback=1" in str(call) for call in self.logs.call_args_list))

    def test_invalid_priority_or_zip_does_not_supersede_valid_reader_scope(self):
        self.server.sync_client_scope("phone", 1)
        process = self.patch(companion, "_run_logged_subprocess")
        for priority, body in [("0", self.archive()), ("invalid", self.archive()), ("4", self.archive()), ("1", b"not a zip")]:
            with self.subTest(priority=priority, body_size=len(body)):
                with self.assertRaises(HTTPError) as caught:
                    self.post_archive(priority=priority, body=body, scope=2)
                self.assertEqual(caught.exception.code, 400)
                self.assertEqual(self.server._client_scopes["phone"], 1)
                self.assertEqual(self.server._chapter_jobs, {})
        process.assert_not_called()

    def test_legacy_upload_defaults_to_one_priority_page_and_still_batches_all_images(self):
        def gpu(**kwargs):
            _, output_dir = self.gpu_paths(kwargs["command"])
            for index in range(3):
                (output_dir / f"{index:04d}.png").write_bytes(self.enhanced)
            return companion.SubprocessRunResult(0, "legacy fixture")

        process = self.patch(companion, "_run_logged_subprocess", side_effect=gpu)
        accepted = self.post_archive(priority=None)
        self.assertEqual(accepted["priority_count"], 1)
        job = self.server._chapter_jobs[accepted["job_id"]]
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        self.assertEqual(self.status(job.job_id)["ready_count"], 3)
        process.assert_called_once()

    def test_abort_cancels_an_uploaded_directory_process_and_prevents_late_results(self):
        started = threading.Event()

        def gpu(**kwargs):
            _, output_dir = self.gpu_paths(kwargs["command"])
            (output_dir / "0000.png").write_bytes(self.enhanced)
            started.set()
            self.assertTrue(kwargs["cancel_event"].wait(3))
            raise RuntimeError("Upscale process was canceled")

        process = self.patch(companion, "_run_logged_subprocess", side_effect=gpu)
        accepted = self.post_archive()
        job = self.server._chapter_jobs[accepted["job_id"]]
        self.addCleanup(job.cancel_event.set)
        self.assertTrue(started.wait(2))
        self.wait_ready(job.job_id, 1)
        with self.request("/api/abort-client", body=b"", scope=2) as response:
            self.assertEqual(response.status, 200)
        self.assertTrue(job.completed.wait(3))
        self.assertTrue(job.cancel_event.is_set())
        self.assertEqual(self.status(job.job_id)["state"], "cancelled")
        with self.assertRaises(HTTPError) as caught:
            self.request(f"/api/upscale-chapter/{job.job_id}/page/0")
        self.assertEqual(caught.exception.code, 410)
        process.assert_called_once()


if __name__ == "__main__":
    unittest.main()
