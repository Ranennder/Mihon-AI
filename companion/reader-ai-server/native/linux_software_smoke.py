"""Exercise the native Vulkan pipeline using Mesa lavapipe on remote CI only."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import subprocess
import time

from PIL import Image, ImageChops, ImageStat


CASES = [
    ("realesr-animevideov3", "realesr-animevideov3-x2", 2),
    ("realesr-animevideov3", "realesr-animevideov3-x3", 3),
    ("realesr-animevideov3", "realesr-animevideov3-x4", 4),
    ("realesrgan-x4plus-anime", "realesrgan-x4plus-anime", 4),
    ("realesrgan-x4plus", "realesrgan-x4plus", 4),
]
MULTI_TILE_CASES = [
    ("realesr-animevideov3", "realesr-animevideov3-x2", 2, "RGB", 35, 73),
    ("realesr-animevideov3", "realesr-animevideov3-x2", 2, "RGBA", 35, 73),
    ("realesrgan-x4plus-anime", "realesrgan-x4plus-anime", 4, "RGB", 33, 35),
]


def run(command: list[str], log: Path, environment: dict[str, str], timeout: int = 600) -> dict:
    started = time.monotonic()
    process = subprocess.run(command, capture_output=True, text=True, errors="replace", env=environment, timeout=timeout)
    output = process.stdout + process.stderr
    log.write_text(output)
    return {"exit_code": process.returncode, "duration_seconds": round(time.monotonic() - started, 3),
            "log": log.name, "output": output}


def validate_image(output: Path, mode: str, scale: int, reference: Path,
                   source_size: tuple[int, int] = (16, 16), tile_size: int | None = None) -> dict:
    comparison = {}
    with Image.open(output) as decoded, Image.open(reference) as expected:
        decoded.load()
        expected.load()
        if decoded.size != tuple(value * scale for value in source_size) or decoded.mode != mode:
            raise RuntimeError(f"Invalid native output geometry/mode: {decoded.size} {decoded.mode}")
        statistics = ImageStat.Stat(decoded.convert("RGB"))
        if not all(math.isfinite(value) for value in statistics.mean + statistics.var) or max(statistics.var) < 1:
            raise RuntimeError("Empty or non-finite native image statistics")
        extrema = decoded.getextrema()
        if mode == "RGBA" and extrema[3][0] == extrema[3][1]:
            raise RuntimeError("The varying alpha channel was lost")
        difference = ImageChops.difference(decoded.convert("RGB"), expected.convert("RGB"))
        error = ImageStat.Stat(difference)
        mean_error = sum(error.mean) / 3
        max_error = max(high for _, high in difference.getextrema())
        # Float32 CPU versus float16 Vulkan is approximate. This generous
        # bound catches channel/padding/shader corruption, not quality changes.
        if mean_error > 12 or max_error > 96:
            raise RuntimeError(f"CPU/Vulkan reference mismatch: mean={mean_error:.3f} max={max_error}")
        comparison.update({"cpu_reference_mean_absolute_error": round(mean_error, 4),
                           "cpu_reference_max_absolute_error": max_error})
        if tile_size is not None:
            bands = []
            band = 2 * scale
            for source_x in range(tile_size, source_size[0], tile_size):
                x = source_x * scale
                bands.append(difference.crop((max(0, x - band), 0, min(decoded.width, x + band), decoded.height)))
            for source_y in range(tile_size, source_size[1], tile_size):
                y = source_y * scale
                bands.append(difference.crop((0, max(0, y - band), decoded.width, min(decoded.height, y + band))))
            if not bands:
                raise RuntimeError("The multi-tile fixture did not cross a tile boundary")
            band_statistics = [ImageStat.Stat(image) for image in bands]
            samples = sum(image.width * image.height * 3 for image in bands)
            seam_mean = sum(sum(statistic.sum) for statistic in band_statistics) / samples
            seam_max = max(high for image in bands for _, high in image.getextrema())
            if seam_mean > 12 or seam_max > 96:
                raise RuntimeError(f"CPU/Vulkan seam mismatch: mean={seam_mean:.3f} max={seam_max}")
            comparison.update({"seam_mean_absolute_error": round(seam_mean, 4),
                               "seam_max_absolute_error": seam_max, "seam_band_count": len(bands),
                               "seam_band_half_width_source_pixels": 2})
    return comparison


def write_source(path: Path, mode: str, width: int, height: int) -> None:
    image = Image.new(mode, (width, height))
    image.putdata([tuple(((x * 17 + y * 11 + channel * 53) % 256)
                        for channel in range(3)) + ((64 + (x * 7 + y * 5) % 192,) if mode == "RGBA" else ())
                   for y in range(height) for x in range(width)])
    image.save(path)


def smoke(root: Path) -> None:
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("This helper runs only on the remote Linux GitHub Actions runner")
    root = root.resolve()
    proof = root / "proof"
    build = root / "build"
    models = root / "models"
    icds = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
    if len(icds) != 1:
        raise RuntimeError(f"Expected exactly one Mesa lavapipe ICD, found {len(icds)}")
    environment = os.environ.copy()
    environment.update({"VK_DRIVER_FILES": str(icds[0]), "VK_ICD_FILENAMES": str(icds[0]),
                        "VK_LOADER_LAYERS_DISABLE": "~implicit~", "LP_NUM_THREADS": "2"})
    for name in ("VK_LOADER_DRIVERS_SELECT", "VK_LOADER_DRIVERS_DISABLE", "VK_LOADER_LAYERS_ENABLE",
                 "VK_LOADER_LAYERS_ALLOW", "VK_INSTANCE_LAYERS"):
        environment.pop(name, None)
    xdg = root / "xdg-runtime"
    xdg.mkdir(mode=0o700)
    environment["XDG_RUNTIME_DIR"] = str(xdg)
    device = run(["vulkaninfo", "--summary"], proof / "vulkan-summary.log", environment)
    if device["exit_code"] or not re.search(r"deviceType\s*=\s*PHYSICAL_DEVICE_TYPE_CPU", device["output"]):
        raise RuntimeError("The selected Vulkan implementation is not a software CPU device")
    if not re.search(r"llvmpipe|lavapipe", device["output"], re.IGNORECASE):
        raise RuntimeError("Mesa lavapipe was not selected")
    result = {"format": "mihon-native-software-vulkan-smoke", "version": 1,
              "checkout_sha": os.environ["GITHUB_SHA"], "software_vulkan_only": True,
              "physical_gpu_tested": False, "icd": icds[0].name,
              "icd_sha256": hashlib.sha256(icds[0].read_bytes()).hexdigest(),
              "cpu_models": [], "vulkan_cases": [], "directory_cases": [], "multi_tile_cases": []}
    result_path = proof / "software-vulkan-result.json"
    result["status"] = "running"
    result_path.write_text(json.dumps(result, indent=2) + "\n")
    try:
        for _, model, scale in CASES:
            record = run([str(build / "mihon-ncnn-smoke"), str(models / f"{model}.param"),
                          str(models / f"{model}.bin"), str(scale)], proof / f"cpu-{model}.log", environment)
            if record["exit_code"] or not re.search(r"CPU_MODEL_SMOKE_OK .*vulkan_created=false", record["output"]):
                raise RuntimeError(f"CPU model smoke failed: {model}; {record['log']}")
            record.pop("output")
            result["cpu_models"].append({"model": model, **record})
            reference = root / f"reference-{model}.ppm"
            reference_record = run([str(build / "mihon-ncnn-smoke"), str(models / f"{model}.param"),
                                    str(models / f"{model}.bin"), str(scale), str(reference)],
                                   proof / f"cpu-reference-{model}.log", environment)
            if reference_record["exit_code"] or not reference.is_file():
                raise RuntimeError(f"CPU image reference failed: {model}")

        for mode in ("RGB", "RGBA"):
            source = root / f"source-{mode}.png"
            write_source(source, mode, 16, 16)
            for model_name, model, scale in CASES:
                label = f"{model}-{mode}"
                output = root / f"output-{label}.png"
                command = [str(build / "realesrgan-ncnn-vulkan"), "-i", str(source), "-o", str(output),
                           "-s", str(scale), "-t", "128", "-j", "1:1:1", "-g", "0", "-n", model_name,
                           "-m", str(models), "-f", "png"]
                record = run(command, proof / f"vulkan-{label}.log", environment)
                if record["exit_code"] or re.search(r"MIHON_GPU_FAULT|failed -\d|VK_ERROR|device lost", record["output"], re.I):
                    raise RuntimeError(f"Software Vulkan smoke failed: {label}; {record['log']}")
                if not re.search(r"llvmpipe|lavapipe", record["output"], re.I):
                    raise RuntimeError(f"Native CLI did not identify the selected software device: {label}")
                comparison = validate_image(output, mode, scale, root / f"reference-{model}.ppm")
                record.pop("output")
                result["vulkan_cases"].append({"model": model, "input_mode": mode, "scale": scale,
                                               "output_size": [16 * scale, 16 * scale],
                                               "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
                                               **comparison, **record})
                result_path.write_text(json.dumps(result, indent=2) + "\n")
                print(f"SOFTWARE_VULKAN_OK model={model} mode={mode} scale={scale}", flush=True)

        # Exercise the same bounded decode/compute/save queues used by chapters,
        # including mixed RGB/RGBA pages in one actual native directory process.
        directory_input = root / "directory-input"
        directory_input.mkdir()
        for index, mode in enumerate(("RGB", "RGBA")):
            (directory_input / f"{index:04d}.png").write_bytes((root / f"source-{mode}.png").read_bytes())
        for model_name, model, scale in CASES:
            directory_output = root / f"directory-output-{model}"
            directory_output.mkdir()
            command = [str(build / "realesrgan-ncnn-vulkan"), "-i", str(directory_input), "-o", str(directory_output),
                       "-s", str(scale), "-t", "128", "-j", "1:1:1", "-g", "0", "-n", model_name,
                       "-m", str(models), "-f", "png"]
            record = run(command, proof / f"directory-{model}.log", environment)
            if record["exit_code"] or re.search(r"MIHON_GPU_FAULT|failed -\d|VK_ERROR|device lost", record["output"], re.I):
                raise RuntimeError(f"Software Vulkan directory smoke failed: {model}; {record['log']}")
            images = []
            for index, mode in enumerate(("RGB", "RGBA")):
                output = directory_output / f"{index:04d}.png"
                images.append({"mode": mode, **validate_image(output, mode, scale, root / f"reference-{model}.ppm"),
                               "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest()})
            record.pop("output")
            result["directory_cases"].append({"model": model, "images": images, **record})
            result_path.write_text(json.dumps(result, indent=2) + "\n")
            print(f"SOFTWARE_VULKAN_DIRECTORY_OK model={model} pages=2", flush=True)

        # Non-square pages cross both tile axes, including a partial final row
        # and column. Keep software execution small; do not enlarge tolerances
        # or the CPU time budget if a real pipeline error is found.
        for model_name, model, scale, mode, width, height in MULTI_TILE_CASES:
            label = f"multi-{model}-{mode}-{width}x{height}"
            source = root / f"source-{label}.png"
            output = root / f"output-{label}.png"
            reference = root / f"reference-{label}.ppm"
            write_source(source, mode, width, height)
            reference_record = run([str(build / "mihon-ncnn-smoke"), str(models / f"{model}.param"),
                                    str(models / f"{model}.bin"), str(scale), str(reference), str(width), str(height)],
                                   proof / f"cpu-reference-{label}.log", environment, timeout=120)
            if reference_record["exit_code"] or not reference.is_file():
                raise RuntimeError(f"CPU multi-tile reference failed: {label}")
            command = [str(build / "realesrgan-ncnn-vulkan"), "-i", str(source), "-o", str(output),
                       "-s", str(scale), "-t", "32", "-j", "1:1:1", "-g", "0", "-n", model_name,
                       "-m", str(models), "-f", "png"]
            record = run(command, proof / f"vulkan-{label}.log", environment, timeout=120)
            if record["exit_code"] or re.search(r"MIHON_GPU_FAULT|failed -\d|VK_ERROR|device lost", record["output"], re.I):
                raise RuntimeError(f"Software Vulkan multi-tile smoke failed: {label}; {record['log']}")
            if not re.search(r"llvmpipe|lavapipe", record["output"], re.I):
                raise RuntimeError(f"Native multi-tile CLI did not identify the selected software device: {label}")
            comparison = validate_image(output, mode, scale, reference, (width, height), tile_size=32)
            record.pop("output")
            result["multi_tile_cases"].append({"model": model, "input_mode": mode, "scale": scale,
                                                "source_size": [width, height], "output_size": [width * scale, height * scale],
                                                "tile_size": 32, "tiles_x": math.ceil(width / 32),
                                                "tiles_y": math.ceil(height / 32),
                                                "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
                                                **comparison, **record})
            result_path.write_text(json.dumps(result, indent=2) + "\n")
            print(f"SOFTWARE_VULKAN_MULTI_TILE_OK model={model} mode={mode} source={width}x{height} tile=32", flush=True)

        successful = run([str(build / "mihon-native-failure-test"), "success"], proof / "native-success.log", environment)
        failed = run([str(build / "mihon-native-failure-test")], proof / "native-injected-fault.log", environment)
        if successful["exit_code"] != 0 or failed["exit_code"] != 71 or "MIHON_GPU_FAULT" not in failed["output"] or "UNREACHABLE_AFTER_GPU_FAULT" in failed["output"]:
            raise RuntimeError("Native fail-fast smoke failed")
        result.update({"status": "passed", "native_failure_exit": failed["exit_code"]})
    except Exception as error:
        result.update({"status": "failed", "error": str(error)})
        raise
    finally:
        result_path.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-root", type=Path, required=True)
    arguments = parser.parse_args()
    smoke(arguments.work_root)
