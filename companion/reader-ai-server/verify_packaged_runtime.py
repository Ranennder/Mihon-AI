"""Verify the native engine inside a PyInstaller EXE without executing it."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any

from native.patch_runtime import (
    GLSLANG_COMMIT, NCNN_COMMIT, REAL_ESRGAN_COMMIT, RUNTIME_VERSION, WEBP_COMMIT,
)


BINARY_NAME = "realesrgan-ncnn-vulkan.exe"
MANIFEST_NAME = "native-runtime-manifest.json"
MODEL_ARCHIVE_SHA256 = "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d"
SOURCE_INPUT_NAMES = ("CMakeLists.txt", "patch_runtime.py", "cpu_model_smoke.cpp", "native_failure_test.cpp")
MODELS = {
    "realesr-animevideov3-x2": 2,
    "realesr-animevideov3-x3": 3,
    "realesr-animevideov3-x4": 4,
    "realesrgan-x4plus-anime": 4,
    "realesrgan-x4plus": 4,
}
EXPECTED_PINS = {
    "realesrgan": REAL_ESRGAN_COMMIT, "ncnn": NCNN_COMMIT,
    "glslang": GLSLANG_COMMIT, "webp": WEBP_COMMIT,
}
EXPECTED_NCNN_PATCH = {
    "disabled_optional_driver_queries": [
        "VK_KHR_cooperative_matrix", "VK_EXT_cooperative_matrix_maintenance1",
        "VK_NV_cooperative_matrix", "VK_NV_cooperative_matrix2", "VK_NV_cooperative_vector",
    ],
    "gpu_cpp_sha256": "829c31c7332bee9b5fcaae871d3f48499b9080d50e637834ca2fab2bd1721719",
}
LICENSE_FILES = {"licenses/Real-ESRGAN.txt", "licenses/NCNN.txt", "licenses/glslang.txt", "licenses/libwebp.txt"}


class RuntimeVerificationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeVerificationError(message)


def sha256(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def relative_name(value: Any) -> str:
    require(isinstance(value, str) and bool(value), "Invalid runtime file name")
    require("\\" not in value and ":" not in value, "Noncanonical runtime file name")
    require(not value.startswith("/") and all(part not in {"", ".", ".."} for part in value.split("/")),
            "Unsafe runtime file name")
    require(str(PurePosixPath(value)) == value, "Noncanonical runtime file name")
    return value


def validate_manifest(manifest: Any, source_root: Path) -> dict[str, str]:
    require(isinstance(manifest, dict), "Native manifest is not an object")
    require(manifest.get("format") == "mihon-native-runtime" and type(manifest.get("version")) is int
            and manifest["version"] == 1, "Unsupported native manifest format")
    require(manifest.get("runtime_version") == RUNTIME_VERSION, "Outdated native runtime version")
    require(manifest.get("pins") == EXPECTED_PINS, "Native source pins do not match this checkout")
    require(manifest.get("model_archive_sha256") == MODEL_ARCHIVE_SHA256, "Unverified official model archive")
    expected_inputs = {name: sha256((source_root / "native" / name).read_bytes()) for name in SOURCE_INPUT_NAMES}
    require(manifest.get("build_inputs") == expected_inputs, "Native build inputs do not match this checkout")
    require(type(manifest.get("native_failure_exit")) is int and manifest["native_failure_exit"] == 71,
            "Native fail-stop test was not verified")
    require(manifest.get("gpu_smoke_performed") is False, "Unexpected GPU smoke-test declaration")
    smoke = manifest.get("cpu_smoke")
    require(isinstance(smoke, list) and len(smoke) == len(MODELS), "Missing CPU model smoke results")
    seen: set[str] = set()
    for row in smoke:
        require(isinstance(row, dict), "Invalid CPU smoke result")
        model = row.get("model")
        require(isinstance(model, str) and model in MODELS and model not in seen, "Duplicate or unknown CPU smoke model")
        seen.add(model)
        require(type(row.get("exit_code")) is int and row["exit_code"] == 0 and row.get("gpu_used") is False,
                "CPU model smoke did not pass without GPU use")
        size = 16 * MODELS[model]
        output = row.get("output")
        require(isinstance(output, str) and re.fullmatch(
            rf"CPU_MODEL_SMOKE_OK width={size} height={size} channels=3 checksum=[0-9]+\.[0-9]+ vulkan_created=false",
            output.strip()) is not None, "CPU model smoke output or geometry is invalid")
    patches = manifest.get("native_patches")
    require(isinstance(patches, dict) and patches.get("runtime_version") == RUNTIME_VERSION,
            "Missing native source patch proof")
    require(patches.get("core") == {"checked_operations": 39, "checked_allocations": 13,
                                    "modern_interp_factories": 3, "null_model_file_checks": 2},
            "Native GPU operation checks were not applied")
    require(patches.get("main") == {"bounded_queue": 1, "deferred_output_allocation": 1,
                                    "wide_model_path_corrections": 2, "checked_page_and_model": 2},
            "Native memory/worker patches were not applied")
    require(patches.get("ncnn_gpu") == EXPECTED_NCNN_PATCH, "Native optional-driver probe patch was not applied")
    patched_hashes = patches.get("patched_sha256")
    require(isinstance(patched_hashes, dict) and set(patched_hashes) == {
        "realesrgan.cpp", "main.cpp", "mihon_runtime_checks.h"}, "Missing patched native source hashes")
    require(all(isinstance(digest, str) and re.fullmatch(r"[a-f0-9]{64}", digest)
                for digest in patched_hashes.values()), "Invalid patched native source hashes")
    files = manifest.get("files")
    require(isinstance(files, dict) and bool(files), "Missing native runtime files")
    normalized: dict[str, str] = {}
    for name, digest in files.items():
        name = relative_name(name)
        require(name.casefold() not in normalized, "Duplicate Windows runtime path")
        require(isinstance(digest, str) and re.fullmatch(r"[a-f0-9]{64}", digest) is not None,
                "Invalid runtime file hash")
        normalized[name.casefold()] = name
    required = {BINARY_NAME} | {f"models/{model}.{suffix}" for model in MODELS for suffix in ("param", "bin")}
    require(set(files) == required | LICENSE_FILES, "Native runtime must contain exactly the verified engine, ten model files and four licenses")
    require({name for name in files if name.startswith("models/")} == required - {BINARY_NAME},
            "Unexpected model data in the runtime")
    require({name for name in files if name.lower().endswith((".exe", ".dll"))} == {BINARY_NAME},
            "Unexpected native binary or old engine alias in the runtime")
    return files


def verify_embedded_runtime(archive: Any, manifest_bytes: bytes, source_root: Path) -> dict[str, Any]:
    require(len(manifest_bytes) <= 65536, "Native manifest is oversized")
    try:
        manifest = json.loads(manifest_bytes)
    except (UnicodeError, ValueError) as exc:
        raise RuntimeVerificationError("Native manifest is invalid JSON") from exc
    files = validate_manifest(manifest, source_root)
    entries: dict[str, str] = {}
    for archived_name in archive.toc:
        name = archived_name.replace("\\", "/")
        if not name.casefold().startswith("runtime/"):
            continue
        relative = relative_name(name[len("runtime/"):])
        key = relative.casefold()
        require(key not in entries, "Duplicate runtime member in packaged EXE")
        entries[key] = archived_name
    expected = {name.casefold() for name in files} | {MANIFEST_NAME}
    require(set(entries) == expected, "Packaged runtime inventory differs from the verified manifest")
    packaged_manifest = archive.extract(entries[MANIFEST_NAME])
    require(packaged_manifest == manifest_bytes, "Packaged native manifest differs from the verified build")
    verified = []
    for name, digest in sorted(files.items()):
        body = archive.extract(entries[name.casefold()])
        require(isinstance(body, bytes) and sha256(body) == digest,
                "Packaged runtime file differs from the verified build: " + name)
        verified.append({"path": "runtime/" + name, "bytes": len(body), "sha256": digest})
    return {
        "format": "mihon-native-runtime-packaging-proof", "version": 1,
        "runtime_packaging_verified": True,
        "packaged_runtime_manifest_sha256": sha256(packaged_manifest),
        "native_runtime_manifest": manifest,
        "packaged_runtime_files": verified,
    }


def verify_archive(archive: Any, runtime_dir: Path, source_root: Path) -> dict[str, Any]:
    with (runtime_dir / MANIFEST_NAME).open("rb") as stream:
        manifest_bytes = stream.read(65537)
    proof = verify_embedded_runtime(archive, manifest_bytes, source_root)
    files = proof["native_runtime_manifest"]["files"]
    disk_files = {path.relative_to(runtime_dir).as_posix() for path in runtime_dir.rglob("*") if path.is_file()}
    require(disk_files == set(files) | {MANIFEST_NAME}, "Runtime directory contains unverified files")
    for name, digest in files.items():
        disk_path = runtime_dir.joinpath(*name.split("/"))
        require(sha256(disk_path.read_bytes()) == digest, "Runtime file differs from its build manifest: " + name)
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--runtime-dir", required=True, type=Path)
    parser.add_argument("--proof", required=True, type=Path)
    arguments = parser.parse_args()
    # Reading PyInstaller's CArchive does not launch the bootloader or runtime.
    from PyInstaller.archive.readers import CArchiveReader
    proof = verify_archive(CArchiveReader(str(arguments.executable)), arguments.runtime_dir,
                           Path(__file__).resolve().parent)
    arguments.proof.parent.mkdir(parents=True, exist_ok=True)
    arguments.proof.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"runtime_packaging_verified": True,
                      "runtime_version": proof["native_runtime_manifest"]["runtime_version"],
                      "packaged_runtime_manifest_sha256": proof["packaged_runtime_manifest_sha256"],
                      "verified_files": len(proof["packaged_runtime_files"])}))


if __name__ == "__main__":
    main()
