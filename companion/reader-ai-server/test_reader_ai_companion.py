"""Companion networking regressions; run with python -m unittest discover here."""
from __future__ import annotations

import gzip
import json
import queue
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from PIL import Image

import reader_ai_companion as companion


class FakeTunnelProcess:
    def __init__(self):
        self.returncode = None
        self.lines = queue.Queue()
        self.stdout = self.read_lines()

    def read_lines(self):
        while (item := self.lines.get()) is not None:
            line, consumed = item
            yield line
            consumed.set()

    def emit(self, line):
        consumed = threading.Event()
        self.lines.put((line, consumed))
        if not consumed.wait(2):
            raise AssertionError("Tunnel log was not consumed")

    def poll(self):
        return self.returncode

    def stop(self):
        self.returncode = 1
        self.lines.put(None)


class CompanionNetworkingTests(unittest.TestCase):
    def setUp(self):
        self.log_patch = patch.object(companion, "_emit_log_line")
        self.log_patch.start()
        self.addCleanup(self.log_patch.stop)
        self.server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config(mode="mock_copy"))
        self.addCleanup(self.server.server_close)

    def start_tunnel(self, process):
        with (
            patch.object(companion.Path, "is_file", return_value=True),
            patch.object(companion.subprocess, "Popen", return_value=process) as popen,
        ):
            companion._start_quick_tunnel(self.server)
        self.addCleanup(self.stop_tunnel, process)
        return popen

    def stop_tunnel(self, process):
        if process.poll() is None:
            self.server.tunnel_state_changed.clear()
            process.stop()
            self.assertTrue(self.server.tunnel_state_changed.wait(2))

    def serve_companion(self):
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(self.server.shutdown)
        return f"http://127.0.0.1:{self.server.server_port}"

    def create_upload_job(self, body, page_index=0):
        self.server.config.mode = "subprocess"
        with TemporaryDirectory() as temporary, patch.object(self.server, "_run_chapter_job"):
            archive_path = Path(temporary) / "chapter.zip"
            with zipfile.ZipFile(archive_path, "w") as zipped:
                zipped.writestr(f"{page_index:04d}.png", body)
            return self.server.create_chapter_job(
                request_id=self.server.next_request_id(), archive_path=archive_path,
                requested_output_format="png", model_name="realesr-animevideov3",
                manga_title="Manga", chapter_title="Chapter", client_id="phone", scope_id=1,
            )

    def test_different_single_page_uploads_do_not_reuse_the_same_image(self):
        first_job, _ = self.create_upload_job(b"page-A")
        second_job, reused = self.create_upload_job(b"page-B")
        self.assertFalse(reused)
        self.assertNotEqual(first_job.job_id, second_job.job_id)

    def test_duplicate_upload_with_identical_content_reuses_pending_job(self):
        first_job, _ = self.create_upload_job(b"same-page")
        second_job, reused = self.create_upload_job(b"same-page")
        self.assertTrue(reused)
        self.assertEqual(first_job.job_id, second_job.job_id)

    def test_same_page_count_with_different_page_indexes_is_not_reused(self):
        first_job, _ = self.create_upload_job(b"same-page", page_index=0)
        second_job, reused = self.create_upload_job(b"same-page", page_index=1)
        self.assertFalse(reused)
        self.assertNotEqual(first_job.job_id, second_job.job_id)

    def test_upload_fallback_does_not_reuse_unfinished_direct_download(self):
        with patch.object(self.server, "_download_and_run_direct_chapter_job"):
            direct_job = self.server.create_direct_chapter_job(
                request_id=self.server.next_request_id(),
                pages=[{"page_index": 0, "url": "https://source.example/page.png"}],
                requested_output_format="png", model_name="realesr-animevideov3",
                manga_title="Manga", chapter_title="Chapter", client_id="phone", scope_id=1,
            )
        upload_job, reused = self.create_upload_job(b"phone-fallback")
        self.assertFalse(reused)
        self.assertNotEqual(direct_job.job_id, upload_job.job_id)

    def test_tunnel_waits_for_connection_and_uses_tcp(self):
        process = FakeTunnelProcess()
        popen = self.start_tunnel(process)
        command = popen.call_args.args[0]
        self.assertEqual(command[command.index("--protocol") + 1], "http2")
        process.emit("INF | https://test-pairing.trycloudflare.com |\n")
        self.assertIsNone(self.server.public_url)
        self.assertFalse(self.server.tunnel_state_changed.is_set())
        process.emit("INF Registered tunnel connection connIndex=0 protocol=http2\n")
        self.assertEqual(self.server.public_url, "https://test-pairing.trycloudflare.com")
        self.assertTrue(self.server.tunnel_state_changed.is_set())

    def test_tunnel_exit_clears_url_and_reports_error(self):
        process = FakeTunnelProcess()
        self.start_tunnel(process)
        process.emit("INF https://test-pairing.trycloudflare.com\n")
        process.emit("INF Registered tunnel connection\n")
        process.emit("ERR Connection failed: network unreachable\n")
        self.stop_tunnel(process)
        self.assertIsNone(self.server.public_url)
        self.assertIn("network unreachable", self.server.tunnel_error)

    def test_running_tunnel_is_reused(self):
        process = FakeTunnelProcess()
        self.start_tunnel(process)
        with patch.object(companion.subprocess, "Popen") as popen:
            companion._start_quick_tunnel(self.server)
        popen.assert_not_called()

    def test_tunnel_start_failure_is_returned_by_pairing_endpoint(self):
        base_url = self.serve_companion()
        with (
            patch.object(companion.Path, "is_file", return_value=True),
            patch.object(companion.subprocess, "Popen", side_effect=OSError("runtime blocked")),
        ):
            with self.assertRaises(HTTPError) as raised:
                urlopen(Request(f"{base_url}/api/enable-internet", data=b""), timeout=2)
        with raised.exception as response:
            self.assertEqual(response.code, 503)
            self.assertIn("runtime blocked", response.read().decode())

    def test_source_install_finds_cloudflared_on_path(self):
        process = FakeTunnelProcess()
        with (
            patch.object(companion.Path, "is_file", return_value=False),
            patch.object(companion.shutil, "which", return_value="/usr/bin/cloudflared"),
            patch.object(companion.subprocess, "Popen", return_value=process) as popen,
        ):
            companion._start_quick_tunnel(self.server)
        self.addCleanup(self.stop_tunnel, process)
        self.assertEqual(popen.call_args.args[0][0], "/usr/bin/cloudflared")

    def test_direct_download_preserves_session_and_avoids_http_compression(self):
        original = BytesIO()
        Image.new("RGB", (2, 2), "white").save(original, "PNG")
        image_bytes = original.getvalue()
        received_headers = []

        class SourceHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                received_headers.append(self.headers)
                use_gzip = "gzip" in self.headers.get("Accept-Encoding", "")
                body = gzip.compress(image_bytes) if use_gzip else image_bytes
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(body)))
                if use_gzip:
                    self.send_header("Content-Encoding", "gzip")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        source = ThreadingHTTPServer(("127.0.0.1", 0), SourceHandler)
        self.addCleanup(source.server_close)
        thread = threading.Thread(target=source.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(source.shutdown)
        manifest = {"pages": [{
            "page_index": 0,
            "url": f"http://127.0.0.1:{source.server_port}/page.png",
            "headers": {
                "Accept-Encoding": "gzip, br",
                "Referer": "https://source.example/chapter",
                "Cookie": "session=example",
                "User-Agent": "Mihon test",
            },
        }]}
        base_url = self.serve_companion()
        requested = Request(
            f"{base_url}/api/upscale-chapter-direct",
            data=json.dumps(manifest).encode(),
            headers={
                "Content-Type": "application/json", "X-Reader-AI-Token": self.server.pairing_token,
                "X-Reader-AI-Page-Count": "22",
            },
        )

        def complete_batch(job, pages, input_dir, output_dir):
            (output_dir / "0000.png").write_bytes(pages[0].input_path.read_bytes())
            return 0

        with patch.object(self.server, "_run_chapter_batch", side_effect=complete_batch) as upscale:
            with urlopen(requested, timeout=2) as response:
                self.assertEqual(response.status, 202)
                job_id = json.load(response)["job_id"]
            job = self.server._chapter_jobs[job_id]
            self.assertTrue(job.completed.wait(2))
        self.assertIsNone(job.error)
        self.assertEqual(job.total_pages, 1)
        self.assertEqual(job.display_page_count, 22)
        upscale.assert_called_once()
        self.assertIs(upscale.call_args.args[0], job)
        self.assertEqual([page.page_index for page in upscale.call_args.args[1]], [0])
        self.assertEqual(received_headers[0]["Accept-Encoding"], "identity")
        self.assertEqual(received_headers[0]["Cookie"], "session=example")
        self.assertEqual(received_headers[0]["Referer"], "https://source.example/chapter")
        with urlopen(f"{base_url}/api/upscale-chapter/{job_id}/page/0", timeout=2) as response:
            self.assertEqual(response.read(), image_bytes)

    def test_direct_download_error_is_available_to_client_without_logging_crash(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = companion.ChapterUpscaleJob(
                job_id="failed", request_id="req-test", created_at=0,
                model_name="realesr-animevideov3", native_scale=2,
                requested_output_format="png", batch_output_format="png",
                workspace=root, input_dir=root, output_dir=root, pages={},
            )
            self.server._chapter_jobs[job.job_id] = job
            # An unsupported URL fails before touching the network.
            self.server._download_and_run_direct_chapter_job(job, [{"page_index": 0, "url": "file:///page.png"}])
            self.assertTrue(job.completed.is_set())
            with self.assertRaisesRegex(RuntimeError, r"Only HTTP\(S\)"):
                self.server.get_chapter_page(job.job_id, 0)

    def test_single_page_zip_returns_job_before_upscale_then_can_be_polled(self):
        self.server.config.mode = "subprocess"
        original = BytesIO()
        Image.new("RGB", (2, 2), "white").save(original, "PNG")
        image_bytes = original.getvalue()
        archive = BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("0000.png", image_bytes)
        release_upscale = threading.Event()
        self.addCleanup(release_upscale.set)
        base_url = self.serve_companion()
        public_headers = {
            "CF-Connecting-IP": "203.0.113.10",
            "X-Reader-AI-Token": self.server.pairing_token,
            "X-Reader-AI-Output-Format": "png",
            "X-Reader-AI-Page-Count": "32",
        }

        def complete_job(job):
            if release_upscale.wait(5):
                (job.output_dir / "0000.png").write_bytes(job.pages[0].input_path.read_bytes())
            else:
                job.error = RuntimeError("Test did not release upscale")
            job.completed.set()

        with patch.object(self.server, "_run_chapter_job", side_effect=complete_job):
            request = Request(
                f"{base_url}/api/upscale-chapter", data=archive.getvalue(), headers=public_headers,
            )
            with urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 202)
                job_id = json.load(response)["job_id"]
            job = self.server._chapter_jobs[job_id]
            self.assertFalse(job.completed.is_set())
            self.assertEqual(job.total_pages, 1)
            self.assertEqual(job.display_page_count, 32)
            page_request = Request(
                f"{base_url}/api/upscale-chapter/{job_id}/page/0", headers=public_headers,
            )
            with urlopen(page_request, timeout=2) as response:
                self.assertEqual(response.status, 202)
                self.assertEqual(json.load(response)["stage"], "upscaling")
            release_upscale.set()
            self.assertTrue(job.completed.wait(2))
            with urlopen(page_request, timeout=2) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(response.read(), image_bytes)

    def test_internet_requests_require_token_and_do_not_expose_pairing_secret(self):
        base_url = self.serve_companion()
        public_headers = {"CF-Connecting-IP": "203.0.113.10"}
        with urlopen(Request(f"{base_url}/health", headers=public_headers), timeout=2) as response:
            health = json.load(response)
        self.assertNotIn("pairing_token", health)
        with self.assertRaises(HTTPError) as raised:
            urlopen(Request(f"{base_url}/api/upscale", data=b"test", headers=public_headers), timeout=2)
        self.assertEqual(raised.exception.code, 403)
        raised.exception.close()
        public_headers["X-Reader-AI-Token"] = self.server.pairing_token
        with urlopen(Request(f"{base_url}/api/upscale", data=b"test", headers=public_headers), timeout=2) as response:
            self.assertEqual(response.read(), b"test")


if __name__ == "__main__":
    unittest.main()
