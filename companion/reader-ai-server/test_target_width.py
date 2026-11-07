"""Width protocol and real Pillow geometry through mocked native inference."""
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

from PIL import Image, ImageDraw, JpegImagePlugin

import reader_ai_companion as companion


def png(size, color=(45, 110, 180)):
    with Image.new("RGB", size, color) as image:
        with BytesIO() as buffer:
            image.save(buffer, "PNG")
            return buffer.getvalue()


class TargetWidthTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        binary = self.root / "realesrgan-ncnn-vulkan.exe"
        binary.touch()
        models = self.root / "models"
        models.mkdir()
        self.config = companion.Config(binary=str(binary), model_dir=str(models), token="fixture-token")
        self.server = companion.ReaderAiServer(("127.0.0.1", 0), self.config)
        self.addCleanup(self.server.server_close)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.opener = build_opener(ProxyHandler({}))
        self.source = png((128, 75))
        self.calls = []
        self.failure = None
        self.logs = self.patch(companion, "_emit_log_line")
        self.patch(companion, "_run_logged_subprocess", side_effect=self.native_model)
        self.patch(companion._DIRECT_PAGE_OPENER, "open", side_effect=self.source_response)

    def patch(self, owner, name, **kwargs):
        patcher = patch.object(owner, name, **kwargs)
        result = patcher.start()
        self.addCleanup(patcher.stop)
        return result

    def source_response(self, *args, **kwargs):
        response = BytesIO(self.source)
        response.headers = {"Content-Length": str(len(self.source))}
        return response

    def native_model(self, **kwargs):
        command = kwargs["command"]
        source = Path(command[command.index("-i") + 1])
        destination = Path(command[command.index("-o") + 1])
        scale = int(command[command.index("-s") + 1])
        output_format = command[command.index("-f") + 1]
        inputs = sorted(source.iterdir()) if source.is_dir() else [source]
        sizes = []
        for path in inputs:
            with Image.open(path) as image:
                sizes.append(image.size)
        self.calls.append({"sizes": sizes, "scale": scale, "directory": source.is_dir(), "format": output_format})
        failure, self.failure = self.failure, None
        if failure == "safe":
            return companion.SubprocessRunResult(1, "vkQueueSubmit failed -4")
        for index, path in enumerate(inputs):
            if failure == "missing" and index == 0:
                continue
            output = destination / f"{path.stem}.{output_format}" if source.is_dir() else destination
            with Image.open(path) as image:
                with image.resize((image.width * scale, image.height * scale), Image.Resampling.NEAREST) as enlarged:
                    if failure == "black" and index == 0:
                        enlarged.paste((0, 0, 0), (0, 0, enlarged.width, enlarged.height))
                    output.write_bytes(companion._encode_pil_image(enlarged, output_format))
        return companion.SubprocessRunResult(0, "fixture native inference")

    def archive(self, count=2):
        body = BytesIO()
        with zipfile.ZipFile(body, "w") as archive:
            for index in range(count):
                archive.writestr(f"{index:04d}.png", self.source)
        return body.getvalue()

    def http(self, path, body=None, width="missing", model=companion.FAST_MODEL_NAME, output="png", **extra):
        headers = {
            "X-Reader-AI-Token": "fixture-token", "X-Reader-AI-Model-Name": model,
            "X-Reader-AI-Input-Format": "png", "X-Reader-AI-Output-Format": output,
            **extra,
        }
        if width != "missing":
            headers["X-Reader-AI-Target-Width"] = str(width)
        return self.opener.open(Request(self.base + path, data=body, headers=headers), timeout=10)

    def submit(self, route, width, model, output="png", mode="classic", count=2):
        if route == "single":
            with self.http("/api/upscale", self.source, width, model, output) as response:
                return [(response.read(), response.headers)]
        if route == "zip":
            path, body = "/api/upscale-chapter", self.archive(count)
        else:
            path = "/api/upscale-chapter-direct"
            body = json.dumps({"pages": [{"page_index": index, "url": "https://fixture.test/page.png"}
                                         for index in range(count)]}).encode()
        with self.http(path, body, width, model, output, **{"X-Reader-AI-Chapter-Mode": mode}) as response:
            accepted = json.load(response)
            expected_width = 0 if width == "missing" else width
            self.assertEqual(accepted["target_width"], expected_width)
            self.assertEqual(response.headers["X-Reader-AI-Target-Width"], str(expected_width))
        job = self.server._chapter_jobs[accepted["job_id"]]
        results = []
        for index in range(count):
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if job.error is not None:
                    raise job.error
                result = self.server.get_chapter_page(job.job_id, index)
                if result is not None:
                    break
                time.sleep(0.01)
            else:
                self.fail("Width-sized chapter page was not delivered")
            with self.http(f"/api/upscale-chapter/{job.job_id}/page/{index}") as response:
                results.append((response.read(), response.headers))
        return results

    def assert_size(self, result, size, width):
        body, headers = result
        with Image.open(BytesIO(body)) as image:
            self.assertEqual(image.size, size)
            self.assertNotEqual(image.getpixel((image.width // 2, image.height - 1)), (0, 0, 0))
        self.assertEqual(headers["X-Reader-AI-Target-Width"], str(width))

    def test_both_models_resize_up_down_and_native_on_every_endpoint(self):
        for model, scale in ((companion.FAST_MODEL_NAME, 2), ("realesrgan-x4plus-anime", 4)):
            for route in ("single", "zip", "direct"):
                for width in (64, 257, 0, "missing"):
                    with self.subTest(model=model, route=route, width=width):
                        self.calls.clear()
                        expected_width = 0 if width == "missing" else width
                        size = ((128 * scale, 75 * scale) if not expected_width else
                                (expected_width, (75 * expected_width + 64) // 128))
                        for result in self.submit(route, width, model):
                            self.assert_size(result, size, expected_width)
                        self.assertEqual(len(self.calls), 1)
                        self.assertEqual(self.calls[0]["scale"], scale)
                        inference_width = (width + scale - 1) // scale if expected_width else 128
                        self.assertTrue(all(size[0] == inference_width for size in self.calls[0]["sizes"]))

    def test_fast_720_source_reaches_exact_2048_with_one_native_pass(self):
        self.source = png((720, 315))
        result = self.submit("single", 2048, companion.FAST_MODEL_NAME, output="jpg")[0]
        self.assert_size(result, (2048, 896), 2048)
        self.assertEqual(self.calls, [{"sizes": [(1024, 448)], "scale": 2, "directory": False, "format": "png"}])
        with Image.open(BytesIO(result[0])) as image:
            self.assertEqual(JpegImagePlugin.get_sampling(image), 0)
        self.assertTrue(any("source=720x315, inference=1024x448 x2, output=2048x896" in str(call)
                            for call in self.logs.call_args_list))

    def test_parallel_direct_chapters_keep_width_for_every_batch(self):
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            with self.subTest(model=model):
                for result in self.submit("direct", 129, model, mode="parallel", count=4):
                    self.assert_size(result, (129, 76), 129)

    def test_custom_width_page_is_delivered_before_directory_process_exits(self):
        finish = threading.Event()
        written = threading.Event()
        self.addCleanup(finish.set)

        def running_model(**kwargs):
            result = self.native_model(**kwargs)
            written.set()
            self.assertTrue(finish.wait(4))
            return result

        with patch.object(companion, "_run_logged_subprocess", side_effect=running_model):
            with self.http("/api/upscale-chapter", self.archive(), 129, output="jpg") as response:
                accepted = json.load(response)
            self.assertTrue(written.wait(2))
            job = self.server._chapter_jobs[accepted["job_id"]]
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if self.server.get_chapter_page(job.job_id, 0) is not None:
                    break
                time.sleep(0.01)
            else:
                self.fail("Custom-width CPU delivery waited for the directory process to exit")
            self.assertFalse(job.completed.is_set())
            with self.http(f"/api/upscale-chapter/{job.job_id}/page/0") as response:
                self.assert_size((response.read(), response.headers), (129, 76), 129)
            finish.set()
            self.assertTrue(job.completed.wait(2))

    def test_invalid_width_is_rejected_before_scope_supersession(self):
        self.server.sync_client_scope("phone", 7)
        for route, body in (("/api/upscale", self.source), ("/api/upscale-chapter", self.archive()),
                            ("/api/upscale-chapter-direct", b'{"pages":[{"page_index":0}]}')):
            for width in ("", "-1", "63", "16385", "2048.0", "+2048", "nan", "true"):
                with self.subTest(route=route, width=width):
                    with self.assertRaises(HTTPError) as error:
                        self.http(route, body, width, **{
                            "X-Reader-AI-Client-Id": "phone", "X-Reader-AI-Work-Scope": "9",
                        })
                    self.assertEqual(error.exception.code, 400)
                    error.exception.close()
                    self.assertEqual(self.server._client_scopes["phone"], 7)
        self.assertFalse(self.calls)
        self.assertFalse(self.server._chapter_jobs)

    def test_width_validation_accepts_bounds_and_rejects_non_integer_internal_values(self):
        for width in (0, 64, 16384):
            self.assertEqual(companion._parse_target_width_header(str(width)), width)
        self.assertEqual(companion._parse_target_width_header(None), 0)
        for width in (True, 128.0, "128", -1, 63, 16385):
            with self.assertRaises(ValueError):
                companion._validate_target_width(width)

    def test_custom_webp_and_pillow_jpeg_dimension_limits_fall_back_to_png(self):
        self.source = png((128, 1))
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            with self.subTest(model=model, format="webp"):
                result = self.submit("single", 16384, model, output="webp")[0]
                self.assert_size(result, (16384, 128), 16384)
                self.assertEqual(result[1]["Content-Type"], "image/png")
            with self.subTest(model=model, format="jpg"):
                source = png((16, 16380))
                result = companion._finish_width_output(
                    companion.ProcessedImage(png((64, 65520)), "png"), model, "jpg", source, 64,
                )
                self.assertEqual(result.output_format, "png")
                with Image.open(BytesIO(result.bytes)) as image:
                    self.assertEqual(image.size, (64, 65520))

    def test_batch_key_and_upload_reuse_include_width(self):
        def pending(width):
            return companion.PendingUpscaleRequest(
                request_id="fixture", body=self.source, input_format="png", requested_output_format="png",
                model_name=companion.FAST_MODEL_NAME, native_scale=2, preferred_output_format="png",
                batch_size=4, is_chunked=False, target_width=width,
            )
        self.assertNotEqual(pending(64).batch_key, pending(128).batch_key)
        headers = {"X-Reader-AI-Client-Id": "phone", "X-Reader-AI-Work-Scope": "1"}
        with patch.object(self.server, "_run_chapter_job"):
            accepted = []
            for width in (64, 128, 128):
                with self.http("/api/upscale-chapter", self.archive(), width, **headers) as response:
                    accepted.append(json.load(response))
            self.assertNotEqual(accepted[0]["job_id"], accepted[1]["job_id"])
            self.assertFalse(accepted[1]["reused_existing_job"])
            self.assertEqual(accepted[1]["job_id"], accepted[2]["job_id"])
            self.assertTrue(accepted[2]["reused_existing_job"])

    def test_single_batch_prepares_each_source_and_finishes_exact_geometry(self):
        requests = [companion.PendingUpscaleRequest(
            request_id=str(index), body=png(size), input_format="png", requested_output_format="jpg",
            model_name=companion.FAST_MODEL_NAME, native_scale=2, preferred_output_format="jpg",
            batch_size=4, is_chunked=False, target_width=257,
        ) for index, size in enumerate(((128, 75), (200, 81)))]
        workspace = self.root / "batch"
        workspace.mkdir()
        results = companion._run_subprocess_batch(self.config, workspace, requests, None)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0]["sizes"], [(129, 76), (129, 52)])
        for request, expected in zip(requests, ((257, 151), (257, 104))):
            with Image.open(BytesIO(results[request.request_id].bytes)) as image:
                self.assertEqual(image.size, expected)
                self.assertEqual(JpegImagePlugin.get_sampling(image), 0)

    def test_directory_missing_black_and_vulkan_fallback_keep_width(self):
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            for route in ("zip", "direct"):
                for failure in ("missing", "black", "safe"):
                    with self.subTest(model=model, route=route, failure=failure):
                        self.failure = failure
                        self.calls.clear()
                        for result in self.submit(route, 129, model):
                            self.assert_size(result, (129, 76), 129)
                        self.assertGreater(len(self.calls), 1)

    def test_single_safe_vulkan_retry_keeps_prepared_geometry(self):
        self.failure = "safe"
        result = self.submit("single", 257, "realesrgan-x4plus-anime")[0]
        self.assert_size(result, (257, 151), 257)
        self.assertEqual([call["sizes"] for call in self.calls], [[(65, 38)], [(65, 38)]])

    def test_extreme_geometry_fails_before_resize_inference_or_scope_supersession(self):
        self.source = png((720, 12000))
        self.server.sync_client_scope("phone", 7)
        with patch.object(Image.Image, "resize") as resize:
            for route, body in (("/api/upscale", self.source), ("/api/upscale-chapter", self.archive(1))):
                with self.subTest(route=route):
                    with self.assertRaises(HTTPError) as error:
                        self.http(route, body, 16384, **{
                            "X-Reader-AI-Client-Id": "phone", "X-Reader-AI-Work-Scope": "9",
                        })
                    self.assertEqual(error.exception.code, 400)
                    self.assertIn("pixel limit", error.exception.read().decode())
                    error.exception.close()
                    self.assertEqual(self.server._client_scopes["phone"], 7)
            manifest = json.dumps({"pages": [{"page_index": 0, "url": "https://fixture.test/page.png"}]}).encode()
            with self.http("/api/upscale-chapter-direct", manifest, 16384) as response:
                accepted = json.load(response)
            job = self.server._chapter_jobs[accepted["job_id"]]
            self.assertTrue(job.completed.wait(3))
            self.assertIn("pixel limit", str(job.error))
            self.assertEqual(self.server.get_chapter_status(job.job_id)["state"], "failed")
            resize.assert_not_called()
        self.assertFalse(self.calls)

    def test_batch_missing_and_safe_fallbacks_keep_width_and_cancel_event(self):
        for failure in ("missing", "safe"):
            with self.subTest(failure=failure):
                workspace = self.root / f"batch-{failure}"
                workspace.mkdir()
                requests = [companion.PendingUpscaleRequest(
                    request_id=f"{failure}-{index}", body=self.source, input_format="png",
                    requested_output_format="png", model_name="realesrgan-x4plus-anime", native_scale=4,
                    preferred_output_format="png", batch_size=4, is_chunked=False, target_width=129,
                ) for index in range(2)]
                self.failure = failure
                with patch.object(companion, "_process_single_request_in_workspace", wraps=companion._process_single_request_in_workspace) as process:
                    results = companion._run_subprocess_batch(self.config, workspace, requests, None)
                for request in requests:
                    with Image.open(BytesIO(results[request.request_id].bytes)) as image:
                        self.assertEqual(image.size, (129, 76))
                for call in process.call_args_list:
                    fallback = call.kwargs.get("request")
                    if fallback is not None:
                        original = next(item for item in requests if fallback.request_id.startswith(item.request_id))
                        self.assertEqual(fallback.target_width, original.target_width)
                        self.assertIs(fallback.cancel_event, original.cancel_event)

    def test_tall_pages_chunk_and_fall_back_to_png_on_every_endpoint(self):
        self.source = png((16, 17000))
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            for route in ("single", "zip", "direct"):
                with self.subTest(model=model, route=route):
                    self.calls.clear()
                    result = self.submit(route, 64, model, output="jpg", count=1)[0]
                    self.assert_size(result, (64, 68000), 64)
                    self.assertEqual(result[1]["Content-Type"], "image/png")
                    self.assertGreater(len(self.calls), 1)
                    self.assertTrue(all(not call["directory"] for call in self.calls))
                    self.assertTrue(all(call["format"] == "png" for call in self.calls))

    def test_odd_width_height_uses_source_aspect_and_preserves_chunk_seams(self):
        with Image.new("RGB", (127, 2100), "red") as image:
            ImageDraw.Draw(image).rectangle((0, 1050, 126, 2099), fill="blue")
            self.source = companion._encode_pil_image(image, "png")
        # Force the long-page path with a tiny threshold, using the actual
        # chunk compositor and PIL pixels rather than a mocked resize result.
        with patch.object(companion, "MAX_SINGLE_OUTPUT_DIMENSION", 100), \
                patch.object(companion, "MAX_CHUNK_OUTPUT_DIMENSION", 64):
            result = self.submit("single", 257, "realesrgan-x4plus-anime")[0]
        self.assert_size(result, (257, 4250), 257)
        self.assertGreater(len(self.calls), 2)
        with Image.open(BytesIO(result[0])) as image:
            self.assertEqual(image.getpixel((128, 10)), (255, 0, 0))
            self.assertEqual(image.getpixel((128, 2020)), (255, 0, 0))
            self.assertEqual(image.getpixel((128, 4040)), (0, 0, 255))
            self.assertEqual(image.getpixel((128, 4240)), (0, 0, 255))


if __name__ == "__main__":
    unittest.main()
