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

from PIL import Image, ImageChops, ImageDraw, ImageStat, JpegImagePlugin

import reader_ai_companion as companion


def png(size, color=(45, 110, 180)):
    with Image.new("RGB", size, color) as image:
        with BytesIO() as buffer:
            image.save(buffer, "PNG")
            return buffer.getvalue()


def thin_text_png(size):
    """Font-independent one-pixel strokes and gaps that preprocessing must retain."""
    with Image.new("RGB", size, "white") as image:
        draw = ImageDraw.Draw(image)
        for x in range(8, size[0] - 8, 2):
            draw.line((x, 8, x, 39), fill="black", width=1)
        for y in range(56, size[1] - 14, 23):
            for x in range(8, size[0] - 10, 13):
                # The stems, crossbar and diagonal resemble small Cyrillic
                # letter parts without depending on an installed system font.
                draw.line((x, y, x, y + 13), fill="black", width=1)
                draw.line((x + 5, y, x + 5, y + 13), fill="black", width=1)
                draw.line((x, y + 7, x + 5, y + 7), fill="black", width=1)
                draw.line((x + 7, y + 13, x + 10, y), fill="black", width=1)
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
        self.native_inputs = []
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
                with image.convert("RGB") as rgb:
                    self.native_inputs.append((image.size, rgb.tobytes()))
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
            if expected_width:
                self.assertEqual(response.headers["X-Reader-AI-Input-Policy"], "native-input-v2")
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
        if width:
            self.assertEqual(headers["X-Reader-AI-Input-Policy"], "native-input-v2")

    def assert_original_native_inputs(self, sources):
        expected = []
        for source in sources:
            with Image.open(BytesIO(source)) as image, image.convert("RGB") as rgb:
                expected.append((image.size, rgb.tobytes()))
        self.assertEqual(self.native_inputs, expected)

    def reference_width_output(self, source, scale, width):
        # The reference never calls the production geometry/preparation helpers:
        # one native pixel-preserving pass, then one final proportional resize.
        with Image.open(BytesIO(source)) as image:
            height = max(1, (image.height * width + image.width // 2) // image.width)
            with image.resize((image.width * scale, image.height * scale), Image.Resampling.NEAREST) as native:
                return native.resize((width, height), Image.Resampling.LANCZOS)

    def test_chapter_page_keeps_width_echo_if_job_expires_after_bytes_are_read(self):
        expected = self.submit("zip", 129, companion.FAST_MODEL_NAME, count=1)[0][0]
        job = next(iter(self.server._chapter_jobs.values()))
        self.assertTrue(job.completed.wait(2))
        with self.http(f"/api/upscale-chapter/{job.job_id}/stream") as response:
            self.assertEqual(response.headers["X-Reader-AI-Target-Width"], "129")
            self.assertEqual(response.headers["X-Reader-AI-Input-Policy"], "native-input-v2")
            self.assertIn(expected, response.read())
        get_page = self.server.get_chapter_page

        def get_then_expire(job_id, page_index):
            result = get_page(job_id, page_index)
            self.assertIsNotNone(result)
            with self.server._chapter_jobs_lock:
                self.server._chapter_jobs.pop(job_id)
            return result

        with patch.object(self.server, "get_chapter_page", side_effect=get_then_expire):
            with self.http(f"/api/upscale-chapter/{job.job_id}/page/0") as response:
                self.assertEqual(response.status, 200)
                body = response.read()
                self.assertEqual(body, expected)
                self.assert_size((body, response.headers), (129, 76), 129)
        self.assertNotIn(job.job_id, self.server._chapter_jobs)

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
                        self.assertTrue(all(size == (128, 75) for size in self.calls[0]["sizes"]))

    def test_fast_720_source_reaches_exact_2048_with_one_native_pass(self):
        self.source = png((720, 315))
        result = self.submit("single", 2048, companion.FAST_MODEL_NAME, output="jpg")[0]
        self.assert_size(result, (2048, 896), 2048)
        self.assertEqual(self.calls, [{"sizes": [(720, 315)], "scale": 2, "directory": False, "format": "png"}])
        with Image.open(BytesIO(result[0])) as image:
            self.assertEqual(JpegImagePlugin.get_sampling(image), 0)
        self.assertTrue(any("source=720x315, inference=720x315 x2, output=2048x896" in str(call)
                            for call in self.logs.call_args_list))

    def test_thin_text_reaches_model_unchanged_before_exact_width_on_every_endpoint(self):
        for size, width in (((800, 96), 1440), ((720, 95), 2048)):
            self.source = thin_text_png(size)
            for model, scale in ((companion.FAST_MODEL_NAME, 2), ("realesrgan-x4plus-anime", 4)):
                with self.reference_width_output(self.source, scale, width) as reference:
                    for route in ("single", "zip", "direct"):
                        with self.subTest(source=size, width=width, model=model, route=route):
                            self.calls.clear()
                            self.native_inputs.clear()
                            body, headers = self.submit(route, width, model, count=1)[0]
                            self.assert_original_native_inputs([self.source])
                            self.assertEqual(len(self.calls), 1)
                            self.assertEqual(self.calls[0]["scale"], scale)
                            self.assertEqual(self.calls[0]["format"], "png")
                            self.assert_size((body, headers), reference.size, width)
                            with Image.open(BytesIO(body)) as actual:
                                with ImageChops.difference(actual, reference) as difference:
                                    self.assertIsNone(difference.getbbox())
                                if size == (800, 96) and scale == 2:
                                    # Pre-shrinking 800 to 720 reduces this to
                                    # about 56; native-first keeps about 105.
                                    with actual.crop((14, 14, 1426, 70)) as strokes:
                                        self.assertGreater(ImageStat.Stat(strokes).stddev[0], 90)

    def test_directory_batch_preserves_each_original_text_image(self):
        sources = [thin_text_png(size) for size in ((800, 96), (720, 95))]
        for model, scale in ((companion.FAST_MODEL_NAME, 2), ("realesrgan-x4plus-anime", 4)):
            with self.subTest(model=model):
                self.calls.clear()
                self.native_inputs.clear()
                requests = [companion.PendingUpscaleRequest(
                    request_id=str(index), body=source, input_format="png", requested_output_format="png",
                    model_name=model, native_scale=scale, preferred_output_format="png",
                    batch_size=4, is_chunked=False, target_width=1440,
                ) for index, source in enumerate(sources)]
                workspace = self.root / f"text-batch-{scale}"
                workspace.mkdir()
                results = companion._run_subprocess_batch(self.config, workspace, requests, None)
                self.assert_original_native_inputs(sources)
                self.assertEqual(len(self.calls), 1)
                self.assertTrue(self.calls[0]["directory"])
                for request in requests:
                    with self.reference_width_output(request.body, scale, 1440) as reference, \
                            Image.open(BytesIO(results[request.request_id].bytes)) as actual, \
                            ImageChops.difference(actual, reference) as difference:
                        self.assertIsNone(difference.getbbox())

    def test_native_area_limit_uses_bounded_target_canvas_and_preserves_chunk_phase(self):
        create_image = Image.new
        # Cover global vertical-first downsampling and horizontal-first
        # upsampling. The whole native canvas exceeds the forced GPU budget.
        for source_size, width, final_size, native_limit in (
            ((128, 2001), 64, (64, 1001), 64 * 1024),
            ((800, 200), 2048, (2048, 512), 512 * 1024),
        ):
            with self.subTest(source=source_size, width=width):
                self.source = thin_text_png(source_size)
                self.calls.clear()
                self.native_inputs.clear()
                allocations = []

                def bounded_canvas(mode, size, *args, **kwargs):
                    allocations.append(size)
                    if size != final_size:
                        self.assertLessEqual(size[0] * size[1], native_limit)
                    return create_image(mode, size, *args, **kwargs)

                with patch.object(companion, "MAX_NATIVE_OUTPUT_PIXELS", native_limit), \
                        patch.object(Image, "new", side_effect=bounded_canvas):
                    body, headers = self.submit("single", width, companion.FAST_MODEL_NAME)[0]
                self.assert_size((body, headers), final_size, width)
                self.assertIn(final_size, allocations)
                self.assertNotIn((source_size[0] * 2, source_size[1] * 2), allocations)
                self.assertGreater(len(self.calls), 2)
                for call in self.calls:
                    self.assertFalse(call["directory"])
                    self.assertEqual(call["format"], "png")
                    for input_width, input_height in call["sizes"]:
                        self.assertEqual(input_width, source_size[0])
                        self.assertLessEqual(input_width * input_height * call["scale"] ** 2, native_limit)
                with self.reference_width_output(self.source, 2, width) as reference, \
                        Image.open(BytesIO(body)) as actual, ImageChops.difference(actual, reference) as difference:
                    # Tiny rounding differences are allowed; local per-strip
                    # phases or missing overlap would corrupt seam rows.
                    self.assertLess(ImageStat.Stat(difference).mean[0], 0.1)
                    self.assertLessEqual(difference.getextrema()[0][1], 1)

    def test_cancelled_custom_width_chunk_never_returns_or_encodes_final_page(self):
        self.source = thin_text_png((128, 1201))
        request = companion.PendingUpscaleRequest(
            request_id="cancelled-width-chunks", body=self.source, input_format="png", requested_output_format="png",
            model_name=companion.FAST_MODEL_NAME, native_scale=2, preferred_output_format="png",
            batch_size=1, is_chunked=False, target_width=64,
        )
        workspace = self.root / "cancelled-width-chunks"
        workspace.mkdir()

        def cancel_after_native(**kwargs):
            result = self.native_model(**kwargs)
            request.cancel_event.set()
            return result

        encoded_sizes = []
        encode_image = companion._encode_pil_image

        def record_encoded_size(image, *args, **kwargs):
            encoded_sizes.append(image.size)
            return encode_image(image, *args, **kwargs)

        with patch.object(companion, "MAX_NATIVE_OUTPUT_PIXELS", 64 * 1024), \
                patch.object(companion, "_run_logged_subprocess", side_effect=cancel_after_native), \
                patch.object(companion, "_encode_pil_image", side_effect=record_encoded_size):
            with self.assertRaisesRegex(RuntimeError, "canceled"):
                companion._process_single_request_in_workspace(self.config, workspace, request, None)
        self.assertEqual(len(self.calls), 1)
        self.assertTrue(request.cancel_event.is_set())
        self.assertNotIn((64, 601), encoded_sizes)

    def test_cmyk_jpeg_is_converted_before_lossless_preparation_on_every_endpoint(self):
        with Image.new("CMYK", (80, 120), (35, 120, 200, 10)) as source:
            buffer = BytesIO()
            source.save(buffer, "JPEG", quality=95)
            self.source = buffer.getvalue()
        with Image.open(BytesIO(self.source)) as decoded:
            self.assertEqual(decoded.mode, "CMYK")
            with decoded.convert("RGB") as rgb:
                expected_color = rgb.getpixel((40, 60))
        # Original dimensions must still normalize an unsupported mode,
        # rather than bypassing preparation and forwarding CMYK to inference.
        prepared, extension = companion._prepare_width_input(self.source, "jpg", 2, 160, "fixture-cmyk")
        self.assertEqual(extension, "png")
        with Image.open(BytesIO(prepared)) as image:
            self.assertEqual((image.mode, image.size), ("RGB", (80, 120)))
            self.assertEqual(image.getpixel((40, 60)), expected_color)
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            for route in ("single", "zip", "direct"):
                with self.subTest(model=model, route=route):
                    self.calls.clear()
                    result = self.submit(route, 128, model, count=1)[0]
                    self.assert_size(result, (128, 192), 128)
                    self.assertEqual(len(self.calls), 1)
                    with Image.open(BytesIO(result[0])) as image:
                        self.assertEqual(image.mode, "RGB")
                        self.assertEqual(image.getpixel((64, 96)), expected_color)

    def test_parallel_direct_chapters_keep_width_for_every_batch(self):
        for model in (companion.FAST_MODEL_NAME, "realesrgan-x4plus-anime"):
            with self.subTest(model=model):
                for result in self.submit("direct", 129, model, mode="parallel", count=4):
                    self.assert_size(result, (129, 76), 129)

    def test_custom_width_page_is_delivered_before_directory_process_exits(self):
        started = threading.Event()
        allow_write = threading.Event()
        finish = threading.Event()
        written = threading.Event()
        self.addCleanup(finish.set)
        self.addCleanup(allow_write.set)

        def running_model(**kwargs):
            started.set()
            self.assertTrue(allow_write.wait(4))
            result = self.native_model(**kwargs)
            written.set()
            self.assertTrue(finish.wait(4))
            return result

        with patch.object(companion, "_run_logged_subprocess", side_effect=running_model):
            with self.http("/api/upscale-chapter", self.archive(), 129, output="jpg") as response:
                accepted = json.load(response)
                self.assertEqual(response.headers["X-Reader-AI-Input-Policy"], "native-input-v2")
            self.assertTrue(started.wait(2))
            with self.http(f"/api/upscale-chapter/{accepted['job_id']}/page/0") as response:
                self.assertEqual(response.status, 202)
                self.assertEqual(response.headers["X-Reader-AI-Target-Width"], "129")
                self.assertEqual(response.headers["X-Reader-AI-Input-Policy"], "native-input-v2")
                self.assertEqual(json.load(response)["target_width"], 129)
            allow_write.set()
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
        self.assertEqual(self.calls[0]["sizes"], [(128, 75), (200, 81)])
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
        self.assertEqual([call["sizes"] for call in self.calls], [[(128, 75)], [(128, 75)]])

    def test_extreme_geometry_fails_before_resize_inference_or_scope_supersession(self):
        # Exercise source and final-area boundaries using metadata only, so the
        # exact limit and one-pixel excess need no enormous image allocations.
        with patch.object(companion, "MAX_TARGET_OUTPUT_PIXELS", 4096):
            inference, final = companion._width_geometry(companion.ImageSize(4096, 1), 2, 64)
            self.assertEqual((inference.width, inference.height), (4096, 1))
            self.assertEqual((final.width, final.height), (64, 1))
            with self.assertRaisesRegex(ValueError, "pixel limit"):
                companion._width_geometry(companion.ImageSize(4097, 1), 2, 64)
            _, final = companion._width_geometry(companion.ImageSize(32, 32), 4, 64)
            self.assertEqual(final.width * final.height, 4096)
        with patch.object(companion, "MAX_TARGET_OUTPUT_PIXELS", 4095):
            with self.assertRaisesRegex(ValueError, "pixel limit"):
                companion._width_geometry(companion.ImageSize(32, 32), 4, 64)
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
                    if model == companion.FAST_MODEL_NAME:
                        self.assertEqual(len(self.calls), 1)
                    else:
                        self.assertGreater(len(self.calls), 1)
                        self.assertTrue(all(not call["directory"] for call in self.calls))
                    self.assertTrue(all(call["format"] == "png" for call in self.calls))

    def test_odd_width_height_uses_source_aspect_and_preserves_chunk_seams(self):
        with Image.new("RGB", (127, 2100), "red") as image:
            ImageDraw.Draw(image).rectangle((0, 1050, 126, 2099), fill="blue")
            self.source = companion._encode_pil_image(image, "png")
        # Force the long-page path with a tiny threshold, using the actual
        # chunk compositor and PIL pixels rather than a mocked resize result.
        with patch.object(companion, "MAX_SINGLE_OUTPUT_DIMENSION", 1024), \
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
