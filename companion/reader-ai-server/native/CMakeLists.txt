cmake_minimum_required(VERSION 3.20)
cmake_policy(SET CMP0091 NEW)
# NCNN/glslang/libwebp have older cmake_minimum_required scopes. Keep their
# CRT selection consistent with our executable instead of reverting to /MD.
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
project(mihon_native_runtime LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
foreach(required MIHON_NCNN_SOURCE MIHON_WEBP_SOURCE MIHON_APP_SOURCE)
    # Untyped -D values from PowerShell retain Windows backslashes. Normalize
    # before CMake re-expands them as source paths (otherwise \U is an escape).
    file(TO_CMAKE_PATH "${${required}}" normalized_source)
    set(${required} "${normalized_source}")
    if(NOT IS_DIRECTORY "${${required}}")
        message(FATAL_ERROR "Missing pinned source: ${required}")
    endif()
endforeach()

# NCNN's current loader includes Vulkan declarations and loads vulkan-1.dll
# dynamically. No old Vulkan SDK or bundled vendor driver is used.
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_SIMPLEVK ON CACHE BOOL "" FORCE)
set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)
set(NCNN_ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_AVX OFF CACHE BOOL "" FORCE)
set(NCNN_AVX2 OFF CACHE BOOL "" FORCE)
set(NCNN_AVX512 OFF CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_BINARIES ON CACHE BOOL "" FORCE)
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(ENABLE_PCH OFF CACHE BOOL "" FORCE)
set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)

# Only operators used by the unchanged official ESRGAN models and NCNN's
# tensor packing/conversion paths are needed. This keeps remote builds bounded.
file(GLOB layer_sources "${MIHON_NCNN_SOURCE}/src/layer/*.cpp")
foreach(layer_source IN LISTS layer_sources)
    get_filename_component(layer_name "${layer_source}" NAME_WE)
    set(WITH_LAYER_${layer_name} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(layer input convolution relu prelu split concat binaryop eltwise interp
              packing cast padding pixelshuffle crop gemm)
    set(WITH_LAYER_${layer} ON CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${MIHON_NCNN_SOURCE}" ncnn)

set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPDEMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${MIHON_WEBP_SOURCE}" webp)

set(shader_headers)
foreach(shader realesrgan_preproc realesrgan_postproc
               realesrgan_preproc_tta realesrgan_postproc_tta)
    foreach(variant plain fp16s int8s)
        set(defines)
        set(suffix "")
        if(NOT variant STREQUAL "plain")
            set(suffix "_${variant}")
            list(APPEND defines -DNCNN_fp16_storage=1)
        endif()
        if(variant STREQUAL "int8s")
            list(APPEND defines -DNCNN_int8_storage=1)
        endif()
        set(output "${CMAKE_CURRENT_BINARY_DIR}/${shader}${suffix}.spv.hex.h")
        add_custom_command(OUTPUT "${output}"
            COMMAND $<TARGET_FILE:glslang-standalone> ${defines} -V -s -x
                    -o "${output}" "${MIHON_APP_SOURCE}/${shader}.comp"
            DEPENDS glslang-standalone "${MIHON_APP_SOURCE}/${shader}.comp"
            VERBATIM)
        list(APPEND shader_headers "${output}")
    endforeach()
endforeach()

add_executable(realesrgan-ncnn-vulkan
    "${MIHON_APP_SOURCE}/main.cpp" "${MIHON_APP_SOURCE}/realesrgan.cpp"
    ${shader_headers})
target_include_directories(realesrgan-ncnn-vulkan PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}" "${MIHON_WEBP_SOURCE}/src")
target_link_libraries(realesrgan-ncnn-vulkan PRIVATE ncnn webp)
if(WIN32)
    target_link_libraries(realesrgan-ncnn-vulkan PRIVATE ole32 windowscodecs)
endif()
target_compile_definitions(realesrgan-ncnn-vulkan PRIVATE NOMINMAX _CRT_SECURE_NO_WARNINGS)

if(WIN32)
    add_executable(mihon-ncnn-smoke cpu_model_smoke.cpp)
else()
    # The software Vulkan preflight shares the exact application, NCNN,
    # shaders and patch set with the packaged Windows build.
    add_executable(mihon-ncnn-smoke linux_cpu_model_smoke.cpp)
endif()
target_link_libraries(mihon-ncnn-smoke PRIVATE ncnn)
target_compile_definitions(mihon-ncnn-smoke PRIVATE _CRT_SECURE_NO_WARNINGS)

# Built and run only in CI; this never initializes Vulkan or touches a GPU.
add_executable(mihon-native-failure-test native_failure_test.cpp)
target_include_directories(mihon-native-failure-test PRIVATE "${MIHON_APP_SOURCE}")
