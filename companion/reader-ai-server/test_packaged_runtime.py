"""Reject stale/tampered native payloads without executing a packaged EXE."""
from __future__ import annotations

import copy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import verify_packaged_runtime as verifier


class MemoryArchive:
    def __init__(self, members):
        self.members = members
        self.toc = dict.fromkeys(members)

    def extract(self, name):
        return self.members[name]


class PackagedRuntimeTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        self.source = self.root / "source"
        (self.source / "native").mkdir(parents=True)
        inputs = {}
        for name in verifier.SOURCE_INPUT_NAMES:
            body = ("current source: " + name).encode()
            (self.source / "native" / name).write_bytes(body)
            inputs[name] = verifier.sha256(body)
        self.manifest = {
            "format": "mihon-native-runtime", "version": 1,
            "runtime_version": verifier.RUNTIME_VERSION,
            "pins": dict(verifier.EXPECTED_PINS), "build_inputs": inputs,
            "model_archive_sha256": verifier.MODEL_ARCHIVE_SHA256,
            "native_failure_exit": 71, "gpu_smoke_performed": False,
            "cpu_smoke": [
                {"model": model, "exit_code": 0, "gpu_used": False,
                 "output": f"CPU_MODEL_SMOKE_OK width={16 * scale} height={16 * scale} channels=3 checksum=1.500000000 vulkan_created=false"}
                for model, scale in verifier.MODELS.items()
            ],
            "native_patches": {
                "runtime_version": verifier.RUNTIME_VERSION,
                "core": {"checked_operations": 39, "checked_allocations": 13,
                         "modern_interp_factories": 3, "null_model_file_checks": 2},
                "main": {"bounded_queue": 1, "deferred_output_allocation": 1,
                         "wide_model_path_corrections": 2, "checked_page_and_model": 2},
                "ncnn_gpu": copy.deepcopy(verifier.EXPECTED_NCNN_PATCH),
                "patched_sha256": {name: "a" * 64 for name in ("realesrgan.cpp", "main.cpp", "mihon_runtime_checks.h")},
            },
            "files": {},
        }
        self.members = {}
        names = [verifier.BINARY_NAME] + [f"models/{name}.{suffix}" for name in verifier.MODELS for suffix in ("param", "bin")]
        names += ["licenses/Real-ESRGAN.txt", "licenses/NCNN.txt", "licenses/glslang.txt", "licenses/libwebp.txt"]
        for name in names:
            body = ("verified payload: " + name).encode()
            path = self.runtime.joinpath(*name.split("/"))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
            self.manifest["files"][name] = verifier.sha256(body)
            self.members["runtime/" + name] = body
        self.store_manifest()

    def store_manifest(self):
        body = json.dumps(self.manifest, indent=2).encode("utf-8")
        (self.runtime / verifier.MANIFEST_NAME).write_bytes(body)
        self.members["runtime/" + verifier.MANIFEST_NAME] = body

    def verify(self):
        return verifier.verify_archive(MemoryArchive(self.members), self.runtime, self.source)

    def test_proof_is_derived_from_the_embedded_manifest_and_all_actual_payload_bytes(self):
        proof = self.verify()
        self.assertTrue(proof["runtime_packaging_verified"])
        self.assertEqual(proof["native_runtime_manifest"], self.manifest)
        self.assertEqual(proof["packaged_runtime_manifest_sha256"], verifier.sha256(self.members["runtime/" + verifier.MANIFEST_NAME]))
        self.assertEqual(len(proof["packaged_runtime_files"]), len(self.manifest["files"]))
        self.assertEqual({row["path"] for row in proof["packaged_runtime_files"]}, {"runtime/" + name for name in self.manifest["files"]})

    def test_old_native_engine_cannot_be_grafted_into_a_new_manifest(self):
        self.members["runtime/" + verifier.BINARY_NAME] = b"old 2022 runtime executable"
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Packaged runtime file differs"):
            self.verify()

    def test_packaged_manifest_must_be_byte_identical_to_the_native_build_manifest(self):
        self.members["runtime/" + verifier.MANIFEST_NAME] += b"\n"
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Packaged native manifest differs"):
            self.verify()

    def test_missing_packaged_model_is_rejected(self):
        del self.members["runtime/models/realesr-animevideov3-x3.bin"]
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "inventory differs"):
            self.verify()

    def test_unverified_old_engine_alias_is_rejected(self):
        self.members["runtime/realesrgan-ncnn.exe"] = b"old alias"
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "inventory differs"):
            self.verify()

    def test_modified_model_in_the_runtime_cache_is_rejected(self):
        (self.runtime / "models" / "realesrgan-x4plus.bin").write_bytes(b"modified weights")
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Runtime file differs"):
            self.verify()

    def test_another_ncnn_revision_cannot_reuse_this_runtime_proof(self):
        self.manifest["pins"]["ncnn"] = "0" * 40
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "source pins"):
            self.verify()

    def test_changed_build_inputs_require_a_fresh_native_build(self):
        (self.source / "native" / "CMakeLists.txt").write_bytes(b"changed compiler options")
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "build inputs"):
            self.verify()

    def test_five_duplicate_smoke_rows_do_not_prove_five_models_were_tested(self):
        self.manifest["cpu_smoke"] = [copy.deepcopy(self.manifest["cpu_smoke"][0]) for _ in range(5)]
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Duplicate or unknown"):
            self.verify()

    def test_gpu_or_failed_smoke_results_are_rejected(self):
        self.manifest["cpu_smoke"][0]["gpu_used"] = True
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "without GPU use"):
            self.verify()

    def test_a_missing_native_fail_stop_check_is_rejected(self):
        self.manifest["native_failure_exit"] = 0
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "fail-stop"):
            self.verify()

    def test_optional_driver_query_patch_is_required_including_its_source_hash(self):
        original = copy.deepcopy(self.manifest["native_patches"]["ncnn_gpu"])
        for bad in ({}, {**original, "gpu_cpp_sha256": "0" * 64},
                    {**original, "disabled_optional_driver_queries": original["disabled_optional_driver_queries"][:-1]}):
            with self.subTest(proof=bad):
                self.manifest["native_patches"]["ncnn_gpu"] = bad
                self.store_manifest()
                with self.assertRaisesRegex(verifier.RuntimeVerificationError, "optional-driver probe patch"):
                    self.verify()

    def test_exact_runtime_inventory_requires_all_four_licenses(self):
        del self.manifest["files"]["licenses/NCNN.txt"]
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "exactly the verified engine"):
            self.verify()

    def test_duplicate_windows_paths_cannot_hide_an_old_binary(self):
        self.members["runtime\\REALESRGAN-NCNN-VULKAN.EXE"] = b"old engine under a case alias"
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Duplicate runtime member"):
            self.verify()

    def test_windows_archive_separators_are_supported_without_extracting_to_disk(self):
        self.members = {name.replace("/", "\\"): body for name, body in self.members.items()}
        self.assertTrue(self.verify()["runtime_packaging_verified"])

    def test_traversal_names_in_runtime_manifest_are_rejected_before_file_reads(self):
        self.manifest["files"]["../outside.exe"] = "a" * 64
        self.store_manifest()
        with self.assertRaisesRegex(verifier.RuntimeVerificationError, "Unsafe runtime file name"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
