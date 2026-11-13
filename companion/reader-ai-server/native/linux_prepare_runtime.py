"""Build the shared pinned runtime on remote Linux CI; never execute on a PC."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
from urllib.request import urlopen
import zipfile

import patch_runtime


MODEL_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip"
MODEL_SHA256 = "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d"
SOURCES = {
    "realesrgan": ("xinntao/Real-ESRGAN-ncnn-vulkan", patch_runtime.REAL_ESRGAN_COMMIT),
    "ncnn": ("Tencent/ncnn", patch_runtime.NCNN_COMMIT),
    "glslang": ("nihui/glslang", patch_runtime.GLSLANG_COMMIT),
    "webp": ("webmproject/libwebp", patch_runtime.WEBP_COMMIT),
}


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def checked(command: list[str], log: Path, timeout: int = 1800) -> None:
    print(f"Running {command[0]}: log={log.name}", flush=True)
    started = time.monotonic()
    with log.open("wb") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        print(log.read_text(errors="replace")[-16000:], flush=True)
        raise RuntimeError(f"{command[0]} returned {result.returncode}; see {log.name}")
    print(f"Completed {command[0]} in {time.monotonic() - started:.1f}s", flush=True)


def pinned_clone(repository: str, commit: str, destination: Path, logs: Path, label: str) -> None:
    if len(commit) != 40:
        raise ValueError(f"Invalid pin: {label}")
    checked(["git", "init", "--quiet", str(destination)], logs / f"{label}-init.log")
    checked(["git", "-C", str(destination), "remote", "add", "origin", f"https://github.com/{repository}.git"],
            logs / f"{label}-remote.log")
    checked(["git", "-C", str(destination), "fetch", "--quiet", "--depth", "1", "origin", commit],
            logs / f"{label}-fetch.log", timeout=300)
    checked(["git", "-C", str(destination), "checkout", "--quiet", "--detach", "FETCH_HEAD"],
            logs / f"{label}-checkout.log")
    actual = subprocess.check_output(["git", "-C", str(destination), "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise RuntimeError(f"Pinned source mismatch: {label}")


def prepare(root: Path) -> None:
    if platform.system() != "Linux" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("This helper runs only on the remote Linux GitHub Actions runner")
    root = root.resolve()
    root.mkdir(parents=True, exist_ok=False)
    logs = root / "proof"
    logs.mkdir()
    native = Path(__file__).resolve().parent
    sources = root / "sources"
    sources.mkdir()
    paths = {name: sources / name for name in SOURCES}
    paths["glslang"] = paths["ncnn"] / "glslang"
    for name, (repository, commit) in SOURCES.items():
        pinned_clone(repository, commit, paths[name], logs, name)
    patched = root / "patched"
    patch_manifest = patch_runtime.prepare(paths["realesrgan"], patched, paths["ncnn"])
    (logs / "patch-manifest.json").write_text(json.dumps(patch_manifest, indent=2) + "\n")
    build = root / "build"
    checked(["cmake", "-S", str(native), "-B", str(build), "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
             f"-DMIHON_NCNN_SOURCE={paths['ncnn']}", f"-DMIHON_WEBP_SOURCE={paths['webp']}",
             f"-DMIHON_APP_SOURCE={patched}"], logs / "configure.log")
    checked(["cmake", "--build", str(build), "--parallel", "2"], logs / "compile.log", timeout=2700)

    # Download model data only. No executable from the old Windows ZIP is used.
    archive_path = root / "official-models.zip"
    with urlopen(MODEL_URL, timeout=120) as response, archive_path.open("wb") as destination:
        while chunk := response.read(1024 * 1024):
            destination.write(chunk)
    if sha256(archive_path) != MODEL_SHA256:
        raise RuntimeError("Official model archive checksum mismatch")
    models = root / "models"
    models.mkdir()
    with zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith("models/"):
                continue
            relative = Path(entry.filename).relative_to("models")
            if len(relative.parts) != 1 or relative.suffix not in {".param", ".bin"}:
                continue
            (models / relative.name).write_bytes(archive.read(entry))
    binaries = ["realesrgan-ncnn-vulkan", "mihon-ncnn-smoke", "mihon-native-failure-test"]
    manifest = {
        "format": "mihon-native-software-vulkan-build", "version": 1,
        "runtime_version": patch_runtime.RUNTIME_VERSION, "checkout_sha": os.environ["GITHUB_SHA"],
        "pins": {name: pin for name, (_, pin) in SOURCES.items()},
        "model_archive_sha256": MODEL_SHA256,
        "build_inputs": {path.name: sha256(path) for path in native.iterdir()
                         if path.is_file() and path.suffix in {".txt", ".cpp", ".py"}},
        "binaries": {name: sha256(build / name) for name in binaries},
        "models": {path.name: sha256(path) for path in sorted(models.iterdir())},
        "physical_gpu_tested": False,
    }
    (logs / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-root", type=Path, required=True)
    arguments = parser.parse_args()
    prepare(arguments.work_root)
