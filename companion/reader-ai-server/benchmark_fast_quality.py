"""CPU codec comparison on synthetic line art; excludes GPU inference and network."""
from __future__ import annotations

import argparse
import json
import statistics
import time
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat

import reader_ai_companion as companion


def page(width, height, monochrome=False):
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    for top in range(12, height - 32, 144):
        draw.rectangle((12, top, width - 13, top + 126), outline="black", width=3)
        for x in range(20, width - 20, 4):
            draw.line((x, top + 12, x + 20, top + 74), fill=(30, 65, 170), width=1)
            draw.line((x + 1, top + 12, x + 21, top + 74), fill=(190, 40, 45), width=1)
        draw.text((24, top + 96), "Synthetic manga lines ABC 123", fill="black")
    if monochrome:
        with image.convert("L") as gray:
            converted = gray.convert("RGB")
        image.close()
        return converted
    return image


def benchmark(width, height, repeats, monochrome=False):
    with page(width, height, monochrome) as image:
        def baseline():
            output = BytesIO()
            # Match WIC's old quality/subsampling settings with Pillow for a
            # comparable CPU codec baseline; this is not a WIC timing claim.
            image.save(output, "JPEG", quality=100, subsampling=2)
            return output.getvalue()

        def improved():
            output = BytesIO()
            image.save(output, "PNG", compress_level=6)
            return companion._finish_fast_output(
                companion.ProcessedImage(output.getvalue(), "png"), companion.FAST_MODEL_NAME, "jpg",
            ).bytes

        measurements = {}
        improved_name = f"lossless_then_{companion.FAST_JPEG_QUALITY}_444"
        for name, encode in (("quality_100_420", baseline), (improved_name, improved)):
            encode()
            durations = []
            for _ in range(repeats):
                started = time.perf_counter()
                data = encode()
                durations.append((time.perf_counter() - started) * 1000)
            with Image.open(BytesIO(data)) as decoded:
                difference = ImageChops.difference(image, decoded.convert("RGB"))
                try:
                    error = sum(ImageStat.Stat(difference).mean) / 3
                finally:
                    difference.close()
            measurements[name] = {"median_ms": round(statistics.median(durations), 3),
                                  "bytes": len(data), "mean_absolute_rgb_error": round(error, 3)}
    measurements["added_codec_ms"] = round(measurements[improved_name]["median_ms"]
                                           - measurements["quality_100_420"]["median_ms"], 3)
    return {"kind": "monochrome" if monochrome else "color", "width": width, "height": height, **measurements}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 20:
        parser.error("repeats must be between 1 and 20")
    report = {"scope": "Synthetic CPU codec benchmark only; GPU, WIC encoder, disk, network excluded",
              "model_scale": 2, "additional_gpu_passes": 0,
              "pages": [benchmark(width, height, args.repeats, monochrome)
                        for monochrome in (False, True) for width, height in ((512, 768), (1536, 2304))]}
    text = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    main()
