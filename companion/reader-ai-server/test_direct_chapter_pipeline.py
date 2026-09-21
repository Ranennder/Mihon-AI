"""Direct chapter download/processing concurrency regressions; no GPU required."""
from __future__ import annotations

import queue
import threading
import time
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib.request import urlopen

from PIL import Image

import reader_ai_companion as companion


class ObservedQueue(queue.Queue):
    """Expose a full producer queue without scheduling-dependent sleeps."""

    def __init__(self, maxsize=0):
        super().__init__(maxsize)
        self.producer_blocked = threading.Event()

    def put(self, item, block=True, timeout=None):
        if self.full():
            self.producer_blocked.set()
        return super().put(item, block=block, timeout=timeout)


class DirectChapterPipelineTests(unittest.TestCase):
    def setUp(self):
        self.patch(companion, "_emit_log_line")
        self.server = companion.ReaderAiServer(
            ("127.0.0.1", 0), companion.Config(mode="subprocess", max_workers=1),
        )
        self.addCleanup(self.server.server_close)
        image = BytesIO()
        Image.new("RGB", (3, 2), "white").save(image, "PNG")
        self.image_bytes = image.getvalue()

    def patch(self, target, name, **kwargs):
        patcher = patch.object(target, name, **kwargs)
        mocked = patcher.start()
        self.addCleanup(patcher.stop)
        return mocked

    def serve(self, server):
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def start_job(self, indexes=(0, 1), pages=None):
        job = self.server.create_direct_chapter_job(
            request_id=self.server.next_request_id(),
            pages=pages if pages is not None else [
                {"page_index": index, "url": f"https://source.example/{index}.png"}
                for index in indexes
            ],
            requested_output_format="png", model_name="realesr-animevideov3",
            manga_title="Manga", chapter_title="Chapter", client_id="phone", scope_id=1,
        )

        def stop_job():
            job.cancel_event.set()
            job.completed.wait(3)

        self.addCleanup(stop_job)
        return job

    def prepare_page(self, job, item, stop_event):
        index = int(item["page_index"])
        input_path = job.input_dir / f"{index:04d}.png"
        input_path.write_bytes(self.image_bytes)
        return companion.PreparedChapterPage(index, input_path, "png")

    def observe_prefetch_queue(self):
        queues = []
        created = threading.Event()

        def create_queue(*args, **kwargs):
            ready_queue = ObservedQueue(*args, **kwargs)
            queues.append(ready_queue)
            created.set()
            return ready_queue

        self.patch(companion.queue, "Queue", side_effect=create_queue)
        return queues, created

    def copy_batch(self, job, batch_pages, input_dir, output_dir):
        self.assertNotEqual(output_dir, job.output_dir)
        for page in batch_pages:
            (output_dir / f"{page.page_index:04d}.png").write_bytes(page.input_path.read_bytes())
        return 0

    def wait_for_page(self, job, index):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            page = self.server.get_chapter_page(job.job_id, index)
            if page is not None:
                return page
            job.completed.wait(0.01)
        self.fail(f"Page {index} was not published while the next download was blocked")

    def test_first_page_is_available_over_http_before_second_download_finishes(self):
        second_requested = threading.Event()
        release_second = threading.Event()
        body = self.image_bytes

        class SourceHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/1.png":
                    second_requested.set()
                    if not release_second.wait(5):
                        self.send_error(504)
                        return
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        source = ThreadingHTTPServer(("127.0.0.1", 0), SourceHandler)
        source.daemon_threads = True
        self.addCleanup(source.server_close)
        source_url = self.serve(source)
        companion_url = self.serve(self.server)
        batch = self.patch(self.server, "_run_chapter_batch", side_effect=self.copy_batch)
        job = self.start_job(pages=[
            {"page_index": index, "url": f"{source_url}/{index}.png"} for index in (0, 1)
        ])
        self.addCleanup(release_second.set)

        self.assertTrue(second_requested.wait(3), "The next download did not start")
        self.assertEqual(self.wait_for_page(job, 0).bytes, body)
        with urlopen(f"{companion_url}/api/upscale-chapter/{job.job_id}/page/0", timeout=2) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read(), body)
        self.assertFalse(job.completed.is_set())
        self.assertFalse(release_second.is_set())
        self.assertEqual(self.server.get_chapter_page_progress(job.job_id, 0), ("ready", 100))
        self.assertEqual(self.server.get_chapter_page_progress(job.job_id, 1)[0], "downloading_to_pc")
        self.assertEqual([page.page_index for page in batch.call_args_list[0].args[1]], [0])

        release_second.set()
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        self.assertEqual(self.server.get_chapter_page(job.job_id, 1).bytes, body)

    def test_prefetch_is_bounded_and_batches_keep_manifest_order(self):
        indexes = [7, 2, 11, 4, 9, 0, 5, 6, 8]
        queues, queue_created = self.observe_prefetch_queue()
        release_first_batch = threading.Event()
        batches = []

        def batch(job, pages, input_dir, output_dir):
            batches.append([page.page_index for page in pages])
            if len(batches) == 1:
                self.assertTrue(release_first_batch.wait(3))
            return self.copy_batch(job, pages, input_dir, output_dir)

        download = self.patch(self.server, "_download_direct_chapter_page", side_effect=self.prepare_page)
        self.patch(self.server, "_run_chapter_batch", side_effect=batch)
        job = self.start_job(indexes=indexes)
        self.addCleanup(release_first_batch.set)

        self.assertTrue(queue_created.wait(3))
        ready_queue = queues[0]
        self.assertTrue(1 <= ready_queue.maxsize <= 4, "The producer queue must be bounded")
        self.assertTrue(ready_queue.producer_blocked.wait(3), "The producer never filled its prefetch queue")
        # One active batch, four queued pages, and at most one page awaiting put().
        self.assertLessEqual(download.call_count, 6)
        self.assertFalse(job.completed.is_set())
        release_first_batch.set()
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        self.assertEqual(batches[0], [indexes[0]])
        self.assertTrue(all(1 <= len(batch_pages) <= 4 for batch_pages in batches))
        self.assertEqual([index for batch_pages in batches for index in batch_pages], indexes)
        self.assertEqual(job.stable_output_pages, set(indexes))
        for index in indexes:
            self.assertEqual(self.server.get_chapter_page(job.job_id, index).bytes, self.image_bytes)

    def test_cancellation_unblocks_a_full_prefetch_queue(self):
        queues, queue_created = self.observe_prefetch_queue()
        producer_threads = set()

        def download(job, item, stop_event):
            producer_threads.add(threading.current_thread())
            return self.prepare_page(job, item, stop_event)

        def batch(job, pages, input_dir, output_dir):
            self.assertTrue(job.cancel_event.wait(3))
            raise RuntimeError("Chapter upscale was canceled")

        self.patch(self.server, "_download_direct_chapter_page", side_effect=download)
        self.patch(self.server, "_run_chapter_batch", side_effect=batch)
        job = self.start_job(indexes=range(20))

        self.assertTrue(queue_created.wait(3))
        ready_queue = queues[0]
        self.assertTrue(1 <= ready_queue.maxsize <= 4, "The producer queue must be bounded")
        self.assertTrue(ready_queue.producer_blocked.wait(3))
        canceled = self.server.abort_client_work("phone")
        self.assertEqual(canceled["chapter_jobs"], 1)
        self.assertTrue(job.completed.wait(3), "A full queue prevented cancellation from completing")
        self.assertTrue(job.cancel_event.is_set())
        self.assertIn("canceled", str(job.error).lower())
        self.assertTrue(producer_threads)
        self.assertTrue(all(not thread.is_alive() for thread in producer_threads))

    def test_late_download_failure_preserves_its_error_and_finished_page(self):
        fail_second_download = threading.Event()
        producer_threads = set()
        original_error = OSError("Source rejected page 2 with HTTP 403")

        def download(job, item, stop_event):
            producer_threads.add(threading.current_thread())
            if item["page_index"] == 1:
                self.assertTrue(fail_second_download.wait(3))
                raise original_error
            return self.prepare_page(job, item, stop_event)

        self.patch(self.server, "_download_direct_chapter_page", side_effect=download)
        self.patch(self.server, "_run_chapter_batch", side_effect=self.copy_batch)
        job = self.start_job()
        self.addCleanup(fail_second_download.set)
        self.assertEqual(self.wait_for_page(job, 0).bytes, self.image_bytes)
        self.assertFalse(job.completed.is_set())
        fail_second_download.set()

        self.assertTrue(job.completed.wait(3))
        self.assertIs(job.error, original_error)
        self.assertFalse(job.cancel_event.is_set())
        self.assertTrue(all(not thread.is_alive() for thread in producer_threads))
        self.assertEqual(self.server.get_chapter_page(job.job_id, 0).bytes, self.image_bytes)
        with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
            self.server.get_chapter_page(job.job_id, 1)

    def test_processing_failure_waits_for_the_downloader_to_stop(self):
        second_download_started = threading.Event()
        producer_observed_stop = threading.Event()
        release_producer = threading.Event()
        producer_threads = set()
        original_error = RuntimeError("GPU processing failed")

        def download(job, item, stop_event):
            producer_threads.add(threading.current_thread())
            if item["page_index"] == 1:
                second_download_started.set()
                self.assertTrue(stop_event.wait(3))
                producer_observed_stop.set()
                self.assertTrue(release_producer.wait(3))
                raise RuntimeError("Downloader stopped")
            return self.prepare_page(job, item, stop_event)

        def batch(job, pages, input_dir, output_dir):
            self.assertTrue(second_download_started.wait(3))
            raise original_error

        self.patch(self.server, "_download_direct_chapter_page", side_effect=download)
        self.patch(self.server, "_run_chapter_batch", side_effect=batch)
        job = self.start_job()
        self.addCleanup(release_producer.set)

        self.assertTrue(producer_observed_stop.wait(3))
        self.assertFalse(job.completed.is_set(), "Completion was exposed with an active downloader")
        release_producer.set()
        self.assertTrue(job.completed.wait(3))
        self.assertIs(job.error, original_error)
        self.assertFalse(job.cancel_event.is_set())
        self.assertTrue(all(not thread.is_alive() for thread in producer_threads))

    def test_partial_batch_output_is_not_published(self):
        partial_written = threading.Event()
        finish_batch = threading.Event()

        def batch(job, pages, input_dir, output_dir):
            self.assertNotEqual(output_dir, job.output_dir)
            output_path = output_dir / "0000.png"
            output_path.write_bytes(self.image_bytes[:10])
            partial_written.set()
            self.assertTrue(finish_batch.wait(3))
            output_path.write_bytes(self.image_bytes)
            return 0

        self.patch(self.server, "_download_direct_chapter_page", side_effect=self.prepare_page)
        self.patch(self.server, "_run_chapter_batch", side_effect=batch)
        job = self.start_job(indexes=(0,))
        self.addCleanup(finish_batch.set)

        self.assertTrue(partial_written.wait(3))
        self.assertIsNone(self.server.get_chapter_page(job.job_id, 0))
        self.assertEqual(job.stable_output_pages, set())
        self.assertFalse(job.completed.is_set())
        finish_batch.set()
        self.assertTrue(job.completed.wait(3))
        self.assertIsNone(job.error)
        self.assertEqual(self.server.get_chapter_page(job.job_id, 0).bytes, self.image_bytes)

    def test_shared_batch_helper_preserves_zip_processing_and_safe_fallback(self):
        root = self.server._workspace_root
        binary = root / "fake-upscaler"
        binary.touch()
        models = root / "models"
        models.mkdir()
        self.server.config.binary = str(binary)
        self.server.config.model_dir = str(models)
        archive = root / "pages.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            for index in (17, 18):
                zipped.writestr(f"{index:04d}.png", self.image_bytes)
        job_holder = []

        def process_batch(**kwargs):
            job = next(iter(self.server._chapter_jobs.values()))
            job_holder.append(job)
            self.assertFalse(job.completed.is_set())
            command = kwargs["command"]
            input_dir = Path(command[command.index("-i") + 1])
            output_dir = Path(command[command.index("-o") + 1])
            self.assertEqual(input_dir, job.input_dir)
            self.assertEqual(output_dir, job.output_dir)
            self.assertEqual(sorted(path.stem for path in input_dir.iterdir()), ["0017", "0018"])
            Image.new("RGB", (3, 2), "black").save(output_dir / "0017.png", "PNG")
            (output_dir / "0018.png").write_bytes(self.image_bytes)
            return companion.SubprocessRunResult(0, "")

        def safe_fallback(**kwargs):
            request = kwargs["request"]
            self.assertFalse(job_holder[0].completed.is_set())
            self.assertIs(request.cancel_event, job_holder[0].cancel_event)
            return companion.ProcessedImage(self.image_bytes, "png")

        process = self.patch(companion, "_run_logged_subprocess", side_effect=process_batch)
        fallback = self.patch(companion, "_process_single_request_in_workspace", side_effect=safe_fallback)
        job, reused = self.server.create_chapter_job(
            request_id=self.server.next_request_id(), archive_path=archive,
            requested_output_format="png", model_name="realesr-animevideov3",
            manga_title="Manga", chapter_title="Chapter", chapter_page_count=40,
        )
        self.assertTrue(job.completed.wait(3))
        self.assertFalse(reused)
        self.assertIsNone(job.error)
        self.assertEqual(job.total_pages, 2)
        self.assertEqual(job.display_page_count, 40)
        process.assert_called_once()
        fallback.assert_called_once()
        self.assertEqual(self.server.get_chapter_page(job.job_id, 17).bytes, self.image_bytes)
        self.assertEqual(self.server.get_chapter_page(job.job_id, 18).bytes, self.image_bytes)

    def test_server_close_keeps_workspace_until_downloader_exits(self):
        download_started = threading.Event()
        release_download = threading.Event()
        files_still_present = []

        def download(job, item, stop_event):
            download_started.set()
            self.assertTrue(job.cancel_event.wait(3))
            self.assertTrue(release_download.wait(3))
            files_still_present.append(job.input_dir.is_dir())
            raise RuntimeError("Chapter upscale was canceled")

        self.patch(self.server, "_download_direct_chapter_page", side_effect=download)
        job = self.start_job(indexes=(0,))
        self.addCleanup(release_download.set)
        self.assertTrue(download_started.wait(3))
        self.server.server_close()
        self.assertTrue(job.input_dir.is_dir())
        self.assertFalse(job.completed.is_set())
        release_download.set()
        self.assertTrue(job.completed.wait(3))
        self.assertEqual(files_still_present, [True])

    def test_cancellation_while_waiting_for_gpu_does_not_launch_process(self):
        waiting = threading.Event()
        finished = threading.Event()
        cancel_event = threading.Event()
        outcomes = []

        class LockedSlot:
            def __init__(self):
                self.slot = threading.Semaphore(0)
                self.release_count = 0

            def acquire(self, *args, **kwargs):
                waiting.set()
                return self.slot.acquire(*args, **kwargs)

            def release(self):
                self.release_count += 1
                self.slot.release()

        slot = LockedSlot()
        self.server._subprocess_slot = slot
        popen = self.patch(companion.subprocess, "Popen", side_effect=AssertionError("Canceled GPU work started"))

        def run():
            try:
                companion._run_logged_subprocess(
                    "cancel-wait", ["unused"], Path.cwd(), 10,
                    cancel_event=cancel_event, process_registry=self.server,
                )
            except Exception as exc:
                outcomes.append(exc)
            finally:
                finished.set()

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(slot.slot.release)
        self.addCleanup(cancel_event.set)

        self.assertTrue(waiting.wait(2))
        cancel_event.set()
        self.assertTrue(finished.wait(2), "Cancellation stayed blocked on the occupied GPU")
        popen.assert_not_called()
        self.assertEqual(slot.release_count, 0, "An unacquired GPU slot was released")
        self.assertEqual(len(outcomes), 1)
        self.assertIsInstance(outcomes[0], RuntimeError)
        self.assertIn("cancel", str(outcomes[0]).lower())

    def test_cancellation_as_gpu_slot_is_acquired_does_not_launch_process(self):
        cancel_event = threading.Event()

        class CancelOnAcquireSlot:
            release_count = 0

            def acquire(self, *args, **kwargs):
                cancel_event.set()
                return True

            def release(self):
                self.release_count += 1

        slot = CancelOnAcquireSlot()
        self.server._subprocess_slot = slot
        popen = self.patch(companion.subprocess, "Popen", side_effect=AssertionError("Canceled GPU work started"))
        with self.assertRaisesRegex(RuntimeError, "(?i)cancel"):
            companion._run_logged_subprocess(
                "cancel-acquired", ["unused"], Path.cwd(), 10,
                cancel_event=cancel_event, process_registry=self.server,
            )
        popen.assert_not_called()
        self.assertEqual(slot.release_count, 1)


if __name__ == "__main__":
    unittest.main()
