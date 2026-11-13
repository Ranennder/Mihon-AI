"""Apply checked, deterministic changes to the pinned official native source.

This does not change network weights or image sizing. The old executable ignores
GPU submission/extraction errors and may keep sending work to a lost device.
The rebuilt executable exits at the first failure and uses bounded native queues.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


REAL_ESRGAN_COMMIT = "37026f49824c5cf84062e7c6a5dd71445dcf610f"
NCNN_COMMIT = "7e36d25cb153092ba87a60530455c7376fedf452"
GLSLANG_COMMIT = "780b6a209132dc74e99458f62ebe5fa7e57c41cf"
WEBP_COMMIT = "4fa21912338357f89e4fd51cf2368325b59e9bd9"
RUNTIME_VERSION = "ncnn-20261020-mihon1"

CHECK_HEADER = r'''#ifndef MIHON_RUNTIME_CHECKS_H
#define MIHON_RUNTIME_CHECKS_H
#include <cstdio>
#include <cstdlib>

inline void mihon_checked(int result, const char* operation)
{
    if (result == 0)
        return;
    // Never submit again or run driver cleanup through a lost VkDevice.
    // The OS reclaims process resources after this immediate, nonzero exit.
    std::fprintf(stderr, "MIHON_GPU_FAULT operation=%s result=%d\n", operation, result);
    std::fflush(stderr);
    std::_Exit(71);
}

template<class Tensor> inline void mihon_nonempty(const Tensor& tensor, const char* operation)
{
    if (tensor.empty())
        mihon_checked(-100, operation);
}
#endif
'''


def replace_exact(text: str, old: str, new: str, count: int) -> str:
    actual = text.count(old)
    if actual != count:
        raise ValueError(f"Pinned source contract changed: {old[:90]!r}; {actual} != {count}")
    return text.replace(old, new)


def patch_core(text: str) -> tuple[str, dict[str, int]]:
    text = replace_exact(text, '#include "realesrgan.h"',
                         '#include "realesrgan.h"\n#include "command.h"\n#include "pipeline.h"\n#include "mihon_runtime_checks.h"', 1)
    # Modern NCNN separates CPU and Vulkan layer factories.
    text = replace_exact(text, 'ncnn::create_layer("Interp")',
                         'ncnn::create_layer_vulkan("Interp")', 3)
    text = replace_exact(text, '    net.opt.use_int8_arithmetic = false;',
                         '    net.opt.use_int8_arithmetic = false;\n'
                         '    net.opt.use_cooperative_matrix = false;', 1)
    for scale in (2, 3, 4):
        text = replace_exact(text, f'    bicubic_{scale}x->destroy_pipeline(net.opt);',
                             f'    if (bicubic_{scale}x) bicubic_{scale}x->destroy_pipeline(net.opt);', 1)
    # A missing model file must never pass a null FILE* into NCNN.
    text, missing_count = re.subn(
        r'(            fwprintf\(stderr, L"_wfopen %ls failed\\n", (?:parampath|modelpath)\.c_str\(\)\);)',
        r'\1\n            return -1;', text)
    if missing_count != 2:
        raise ValueError("Pinned Windows model-file checks changed")

    # Every fallible operation is checked before issuing any later GPU work.
    checked_pattern = re.compile(
        r'(?m)^(\s*)((?:net\.(?:load_param|load_model)|'
        r'realesrgan_(?:preproc|postproc)->create|'
        r'bicubic_[234]x->(?:load_param|create_pipeline|forward)|'
        r'ex\.(?:input|extract)|cmd\.(?:submit_and_wait|reset))\([^;\n]*\));\s*$')
    text, checked_count = checked_pattern.subn(
        lambda match: match[1] + 'mihon_checked(' + match[2] + ', "' +
        match[2].split('(', 1)[0] + '");', text)
    if checked_count != 39:
        raise ValueError(f"Pinned GPU check inventory changed: {checked_count} != 39")
    allocation_pattern = re.compile(r'(?m)^(\s*)(\w+(?:\[\d+\])?)\.create\(([^;\n]*)\);\s*$')
    text, allocation_count = allocation_pattern.subn(
        lambda match: match[0].rstrip() + '\n' + match[1] +
        f'mihon_nonempty({match[2]}, "allocate {match[2]}");', text)
    if allocation_count != 13:
        raise ValueError(f"Pinned GPU allocation inventory changed: {allocation_count} != 13")
    return text, {"checked_operations": checked_count, "checked_allocations": allocation_count,
                  "modern_interp_factories": 3, "null_model_file_checks": missing_count}


def patch_main(text: str) -> tuple[str, dict[str, int]]:
    text = replace_exact(text, '#include "realesrgan.h"',
                         '#include "realesrgan.h"\n#include "mihon_runtime_checks.h"', 1)
    # The pinned upstream passes a std::string object to a wide printf vararg!
    text = replace_exact(text, 'std::to_string(scale));', 'std::to_wstring(scale).c_str());', 2)
    text = replace_exact(text, 'while (tasks.size() >= 8)', 'while (tasks.size() >= 1)', 1)
    text = replace_exact(text, '            v.id = i;', '            v.id = i;\n            v.webp = webp;', 1)
    text = replace_exact(text,
        '            v.outimage = ncnn::Mat(w * scale, h * scale, (size_t)c, c);',
        '            // Allocate only when this page owns the compute worker.\n'
        '            // Decoding ahead must not preallocate eight native outputs.', 1)
    text = replace_exact(text, '        realesrgan->process(v.inimage, v.outimage);',
        '        const long long output_pixels = (long long)v.inimage.w * v.inimage.h *\n'
        '                                        realesrgan->scale * realesrgan->scale;\n'
        '        if (v.inimage.empty() || output_pixels <= 0 || output_pixels > 128ll * 1024 * 1024)\n'
        '            mihon_checked(-100, "native output size limit");\n'
        '        v.outimage = ncnn::Mat(v.inimage.w * realesrgan->scale,\n'
        '                               v.inimage.h * realesrgan->scale,\n'
        '                               (size_t)v.inimage.elempack, v.inimage.elempack);\n'
        '        mihon_nonempty(v.outimage, "native output allocation");\n'
        '        mihon_checked(realesrgan->process(v.inimage, v.outimage), "page process");', 1)
    text = replace_exact(text, '            realesrgan[i]->load(paramfullpath, modelfullpath);',
        '            mihon_checked(realesrgan[i]->load(paramfullpath, modelfullpath), "model load");', 1)
    text = replace_exact(text, '        jobs_proc[i] = std::min(jobs_proc[i], gpu_queue_count);',
        '        jobs_proc[i] = std::min(1, std::min(jobs_proc[i], gpu_queue_count));', 1)
    text = replace_exact(text, '            realesrgan[i]->tilesize = tilesize[i];',
        '            realesrgan[i]->tilesize = std::max(32, std::min(256, tilesize[i]));', 1)
    return text, {"bounded_queue": 1, "deferred_output_allocation": 1,
                  "wide_model_path_corrections": 2, "checked_page_and_model": 2}


def patch_ncnn_gpu(source: Path) -> dict:
    path = source / "src" / "gpu.cpp"
    text = path.read_text(encoding="utf-8")
    before = '    const bool support_VK_VERSION_1_1 = physicalDeviceProperties.apiVersion >= VK_MAKE_VERSION(1, 1, 0);'
    disabled = (
        "VK_KHR_cooperative_matrix", "VK_EXT_cooperative_matrix_maintenance1",
        "VK_NV_cooperative_matrix", "VK_NV_cooperative_matrix2", "VK_NV_cooperative_vector",
    )
    block = (
        "    // Mihon: these models use ordinary convolution shaders, never cooperative\n"
        "    // matrix/vector instructions. Avoid querying unused driver extension\n"
        "    // properties (including the NVIDIA 50-series initialization path in\n"
        "    // upstream ncnn issue #6843). This precedes feature/property probes.\n"
        + "".join(f"    support_{extension} = 0;\n" for extension in disabled) + "\n" + before
    )
    text = replace_exact(text, before, block, 1)
    path.write_text(text, encoding="utf-8", newline="\n")
    return {"disabled_optional_driver_queries": list(disabled),
            "gpu_cpp_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def prepare(source: Path, destination: Path, ncnn_source: Path | None = None) -> dict:
    source = source.resolve()
    destination = destination.resolve()
    if destination == source or destination in source.parents or source in destination.parents:
        raise ValueError("Patched source must be a separate sibling directory")
    if destination.exists():
        raise ValueError("Patched destination already exists")
    shutil.copytree(source / "src", destination,
                    ignore=shutil.ignore_patterns("ncnn", "libwebp"))
    cpp_path = destination / "realesrgan.cpp"
    main_path = destination / "main.cpp"
    cpp, core_checks = patch_core(cpp_path.read_text(encoding="utf-8"))
    main, main_checks = patch_main(main_path.read_text(encoding="utf-8"))
    cpp_path.write_text(cpp, encoding="utf-8", newline="\n")
    main_path.write_text(main, encoding="utf-8", newline="\n")
    (destination / "mihon_runtime_checks.h").write_text(CHECK_HEADER, encoding="utf-8", newline="\n")
    checks = {"runtime_version": RUNTIME_VERSION, "core": core_checks, "main": main_checks,
              "patched_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in (cpp_path, main_path, destination / "mihon_runtime_checks.h")}}
    if ncnn_source is not None:
        checks["ncnn_gpu"] = patch_ncnn_gpu(ncnn_source)
    (destination / "patch-manifest.json").write_text(json.dumps(checks, indent=2) + "\n", encoding="utf-8")
    return checks


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--ncnn-source", type=Path)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.source, arguments.destination, arguments.ncnn_source), indent=2))
