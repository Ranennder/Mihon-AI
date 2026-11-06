"""Fast-profile output quality and pipeline regressions; no GPU required."""
from __future__ import annotations

import tempfile
import threading
import time
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageChops, ImageDraw, ImageStat, JpegImagePlugin

import reader_ai_companion as companion


def colored_lines(size=(128, 64)):
    image = Image.new("RGB", size, "white")
    draw = ImageDraw.Draw(image)
    for x in range(size[0]):
        draw.line((x, 0, x, size[1] // 2), fill=(220, 25, 40) if x % 2 else (25, 80, 220))
    draw.text((3, size[1] // 2 + 4), "ABC 123", fill="black")
    return image


def png(image):
    buffer = BytesIO()
    image.save(buffer, "PNG", compress_level=1)
    return buffer.getvalue()


def monochrome_lines():
    image = Image.new("RGB", (384, 256), "white")
    draw = ImageDraw.Draw(image)
    for x in range(3, 340, 7):
        draw.line((x, 2, x + 40, 180), fill="black", width=1)
        draw.line((x + 1, 2, x + 41, 180), fill=(105, 105, 105), width=1)
    draw.text((5, 208), "Thin monochrome ABC 123 small text", fill="black")
    return image


class FastOutputQualityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binary = self.root / "realesrgan-ncnn-vulkan.exe"
        self.binary.touch()
        self.models = self.root / "models"
        self.models.mkdir()
        self.config = companion.Config(binary=str(self.binary), model_dir=str(self.models))
        self.source = colored_lines((64, 48))
        self.addCleanup(self.source.close)
        self.input = png(self.source)
        self.calls = []
        self.patcher = patch.object(companion, "_run_logged_subprocess", side_effect=self.run_fake_model)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def run_fake_model(self, **kwargs):
        command = kwargs["command"]
        self.calls.append(command)
        input_path = Path(command[command.index("-i") + 1])
        output_path = Path(command[command.index("-o") + 1])
        scale = int(command[command.index("-s") + 1])
        output_format = command[command.index("-f") + 1]
        paths = sorted(input_path.iterdir()) if input_path.is_dir() else [input_path]
        for path in paths:
            target = output_path / f"{path.stem}.{output_format}" if output_path.is_dir() else output_path
            with Image.open(path) as source:
                enlarged = source.resize((source.width * scale, source.height * scale), Image.Resampling.NEAREST)
                try:
                    target.write_bytes(png(enlarged))
                finally:
                    enlarged.close()
        return companion.SubprocessRunResult(returncode=0, details="fixture model completed")

    def request(self, request_id="test", output="jpg"):
        return companion.PendingUpscaleRequest(
            request_id=request_id, body=self.input, input_format="png",
            requested_output_format=output, model_name=companion.FAST_MODEL_NAME,
            native_scale=2, preferred_output_format=output, batch_size=4, is_chunked=False,
        )

    def assert_jpeg_444(self, result, size):
        self.assertIn(result.output_format, {"jpg", "jpeg"})
        with Image.open(BytesIO(result.bytes)) as image:
            self.assertEqual(image.format, "JPEG")
            self.assertEqual(image.size, size)
            self.assertEqual(JpegImagePlugin.get_sampling(image), 0)

    def test_lossless_inference_is_only_used_for_fast_requested_jpeg(self):
        for requested in ("jpg", "jpeg"):
            self.assertEqual(companion._inference_output_format(companion.FAST_MODEL_NAME, requested), "png")
            self.assertEqual(companion._inference_output_format("realesrgan-x4plus-anime", requested), requested)
        for requested in ("png", "webp"):
            self.assertEqual(companion._inference_output_format(companion.FAST_MODEL_NAME, requested), requested)

    def test_444_reduces_actual_color_edge_error_compared_with_quality_100_420(self):
        original = colored_lines()
        self.addCleanup(original.close)
        result = companion._finish_fast_output(
            companion.ProcessedImage(png(original), "png"), companion.FAST_MODEL_NAME, "jpg",
        )
        baseline = BytesIO()
        original.save(baseline, "JPEG", quality=100, subsampling=2)
        errors = []
        for data in (baseline.getvalue(), result.bytes):
            with Image.open(BytesIO(data)) as decoded:
                diff = ImageChops.difference(original, decoded.convert("RGB"))
                try:
                    errors.append(sum(ImageStat.Stat(diff).mean) / 3)
                finally:
                    diff.close()
        self.assertLess(errors[1], errors[0] * 0.2)
        self.assert_jpeg_444(result, original.size)

    def test_png_request_and_other_model_are_byte_identical(self):
        original = companion.ProcessedImage(self.input, "png")
        self.assertIs(companion._finish_fast_output(original, companion.FAST_MODEL_NAME, "png"), original)
        self.assertIs(companion._finish_fast_output(original, "realesrgan-x4plus-anime", "jpg"), original)

    def test_monochrome_thin_lines_and_text_keep_quality_100_pixel_error(self):
        with monochrome_lines() as original:
            result = companion._finish_fast_output(
                companion.ProcessedImage(png(original), "png"), companion.FAST_MODEL_NAME, "jpg",
            )
            baseline = BytesIO()
            original.save(baseline, "JPEG", quality=100, subsampling=2)
            errors = []
            for data in (baseline.getvalue(), result.bytes):
                with Image.open(BytesIO(data)) as decoded:
                    diff = ImageChops.difference(original, decoded.convert("RGB"))
                    try:
                        errors.append(sum(ImageStat.Stat(diff).mean) / 3)
                    finally:
                        diff.close()
            self.assertLessEqual(errors[1], errors[0])
            self.assert_jpeg_444(result, original.size)

    def test_png_over_jpeg_dimension_limit_is_not_reencoded(self):
        with Image.new("RGB", (1, companion.FAST_JPEG_MAX_DIMENSION + 1), "white") as image:
            original = companion.ProcessedImage(png(image), "png")
        with patch.object(companion, "_encode_pil_image") as encode:
            self.assertIs(companion._finish_fast_output(original, companion.FAST_MODEL_NAME, "jpg"), original)
        encode.assert_not_called()

    def test_single_page_keeps_one_two_times_gpu_pass_and_returns_444_jpeg(self):
        workspace = self.root / "single"
        workspace.mkdir()
        result = companion._process_single_request_in_workspace(self.config, workspace, self.request(), None)
        self.assert_jpeg_444(result, (128, 96))
        self.assertEqual(len(self.calls), 1)
        command = self.calls[0]
        self.assertEqual(command[command.index("-f") + 1], "png")
        self.assertEqual(command[command.index("-s") + 1], "2")
        self.assertNotIn("-x", command)

    def test_single_page_explicit_png_is_lossless_and_keeps_dimensions(self):
        workspace = self.root / "png"
        workspace.mkdir()
        result = companion._process_single_request_in_workspace(self.config, workspace, self.request(output="png"), None)
        self.assertEqual(result.output_format, "png")
        with Image.open(BytesIO(result.bytes)) as image:
            self.assertEqual(image.format, "PNG")
            self.assertEqual(image.size, (128, 96))
            with self.source.resize(image.size, Image.Resampling.NEAREST) as expected:
                self.assertIsNone(ImageChops.difference(image, expected).getbbox())

    def test_batch_keeps_one_gpu_call_and_finalizes_each_requested_jpeg(self):
        workspace = self.root / "batch"
        workspace.mkdir()
        result = companion._run_subprocess_batch(self.config, workspace, [self.request("one"), self.request("two")], None)
        self.assertEqual(set(result), {"one", "two"})
        for value in result.values():
            self.assert_jpeg_444(value, (128, 96))
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0][self.calls[0].index("-f") + 1], "png")

    def test_canceled_second_client_does_not_discard_live_batch_result(self):
        for during_encode in (False, True):
            with self.subTest(during_encode=during_encode):
                first, second = self.request("live"), self.request("canceled")
                first.client_id, second.client_id = "one", "two"
                first.scope_id = second.scope_id = 1
                server = companion.ReaderAiServer(("127.0.0.1", 0), self.config)
                self.addCleanup(server.server_close)
                workspace = self.root / f"cancel-batch-{during_encode}"
                workspace.mkdir()
                encode = companion._encode_pil_image
                encode_count = 0

                def model_then_cancel(**kwargs):
                    result = self.run_fake_model(**kwargs)
                    if not during_encode:
                        second.cancel_event.set()
                    return result

                def encode_then_cancel(*args, **kwargs):
                    nonlocal encode_count
                    result = encode(*args, **kwargs)
                    encode_count += 1
                    if during_encode and encode_count == 2:
                        second.cancel_event.set()
                    return result

                with patch.object(companion, "_run_logged_subprocess", side_effect=model_then_cancel), \
                        patch.object(companion, "_encode_pil_image", side_effect=encode_then_cancel), \
                        patch.object(server, "_take_next_request", side_effect=[first, companion._STOP_PROCESSING]), \
                        patch.object(server, "_collect_batch", return_value=[first, second]):
                    server._worker_loop(workspace)
                self.assertIsNone(first.error)
                self.assert_jpeg_444(first.result, (128, 96))
                self.assertIsNone(second.result)
                self.assertRegex(str(second.error), "canceled")
                self.assertTrue(first.completed.is_set())
                self.assertTrue(second.completed.is_set())
                self.assertEqual(encode_count, 2 if during_encode else 1)

    def test_chunk_stitching_encodes_444_only_after_lossless_chunks(self):
        workspace = self.root / "chunks"
        workspace.mkdir()
        with colored_lines((8, 600)) as image:
            body = png(image)
        with patch.object(companion, "MAX_CHUNK_OUTPUT_DIMENSION", 16):
            result = companion._run_chunked_subprocess(
                self.config, "chunked", workspace, body, "jpg", companion.FAST_MODEL_NAME, 2, 2,
            )
        self.assert_jpeg_444(result, (16, 1200))
        self.assertEqual(len(self.calls), 2)
        self.assertTrue(all(command[command.index("-f") + 1] == "png" for command in self.calls))

    def test_completed_chapter_png_snapshot_is_returned_as_444_jpeg_without_modification(self):
        server = companion.ReaderAiServer(("127.0.0.1", 0), self.config)
        self.addCleanup(server.server_close)
        input_dir = self.root / "chapter-in"
        output_dir = self.root / "chapter-out"
        input_dir.mkdir()
        output_dir.mkdir()
        input_path = input_dir / "0000.png"
        input_path.write_bytes(self.input)
        prepared = companion.PreparedChapterPage(0, input_path, "png")
        job = companion.ChapterUpscaleJob(
            "fixture", "req", 0, companion.FAST_MODEL_NAME, 2, "jpg", "jpg",
            self.root, input_dir, output_dir, {0: prepared},
        )
        server._chapter_jobs[job.job_id] = job
        self.assertEqual(server._run_chapter_batch(job, [prepared], input_dir, output_dir), 0)
        job.completed.set()
        snapshot = output_dir / "0000.png"
        original_snapshot = snapshot.read_bytes()
        with patch.object(companion, "_encode_pil_image", wraps=companion._encode_pil_image) as encode:
            deadline = time.monotonic() + 3
            result = None
            while result is None and time.monotonic() < deadline:
                result = server.get_chapter_page(job.job_id, 0)
                time.sleep(0.001)
            self.assertIsNotNone(result)
            self.assertEqual(server.get_chapter_page(job.job_id, 0), result)
            self.assertEqual(encode.call_count, 1, "A repeated page GET re-encoded the full bitmap")
        self.assert_jpeg_444(result, (128, 96))
        self.assertEqual(snapshot.read_bytes(), original_snapshot)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0][self.calls[0].index("-f") + 1], "png")

    def test_chunk_stitching_keeps_png_when_jpeg_encoder_cannot_represent_dimensions(self):
        workspace = self.root / "oversize-chunks"
        workspace.mkdir()
        with patch.object(companion, "FAST_JPEG_MAX_DIMENSION", 64):
            result = companion._run_chunked_subprocess(
                self.config, "oversize", workspace, self.input, "jpg", companion.FAST_MODEL_NAME, 2, 2,
            )
        self.assertEqual(result.output_format, "png")
        with Image.open(BytesIO(result.bytes)) as image:
            self.assertEqual(image.size, (128, 96))
            self.assertEqual(image.format, "PNG")

    def test_cancellation_during_final_encode_never_returns_image(self):
        canceled = threading.Event()
        encode = companion._encode_pil_image

        def cancel_after_encode(*args, **kwargs):
            data = encode(*args, **kwargs)
            canceled.set()
            return data

        with patch.object(companion, "_encode_pil_image", side_effect=cancel_after_encode):
            with self.assertRaisesRegex(RuntimeError, "canceled"):
                companion._finish_fast_output(
                    companion.ProcessedImage(self.input, "png"), companion.FAST_MODEL_NAME, "jpg", canceled,
                )

    def test_failed_fast_png_encode_is_not_retried_as_another_gpu_png_pass(self):
        workspace = self.root / "failure"
        workspace.mkdir()
        with patch.object(companion, "_run_subprocess_once", side_effect=RuntimeError("encode image failed")) as run:
            with self.assertRaisesRegex(RuntimeError, "encode image failed"):
                companion._process_single_request_in_workspace(self.config, workspace, self.request(), None)
        run.assert_called_once()

    @unittest.skipUnless(companion.os.name == "nt", "Windows-only updater")
    def test_companion_prerelease_never_installs_an_older_or_equal_latest_release(self):
        for latest in ("v0.1.29", "v0.1.30"):
            with self.subTest(latest=latest), \
                    patch.object(companion.sys, "frozen", True, create=True), \
                    patch.object(companion, "_detect_current_release_tag", return_value="v0.1.30"), \
                    patch.object(companion, "_fetch_latest_release_payload", return_value={"tag_name": latest}), \
                    patch.object(companion, "_download_release_asset") as download, \
                    patch.object(companion, "_launch_windows_self_replace") as replace, \
                    patch.object(companion, "_emit_log_line"):
                self.assertFalse(companion._maybe_perform_self_update(skip_self_update=False))
                download.assert_not_called()
                replace.assert_not_called()


if __name__ == "__main__":
    unittest.main()
