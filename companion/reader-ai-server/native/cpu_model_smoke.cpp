#include "net.h"
#include <cmath>
#include <cstdio>
#include <cwchar>
#include <cstdlib>

// Exercise the exact modern NCNN/model pair without creating a Vulkan instance.
int wmain(int argc, wchar_t** argv)
{
    if (argc != 4)
        return 2;
    const int scale = _wtoi(argv[3]);
    if (scale < 2 || scale > 4)
        return 2;
    ncnn::Net net;
    net.opt.use_vulkan_compute = false;
    net.opt.num_threads = 1;
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_arithmetic = false;
    FILE* param = _wfopen(argv[1], L"rb");
    if (!param)
        return 3;
    const int param_result = net.load_param(param);
    fclose(param);
    if (param_result != 0)
        return 4;
    FILE* weights = _wfopen(argv[2], L"rb");
    if (!weights)
        return 5;
    const int weight_result = net.load_model(weights);
    fclose(weights);
    if (weight_result != 0)
        return 6;
    ncnn::Mat input(16, 16, 3);
    for (int channel = 0; channel < 3; channel++)
    {
        float* values = input.channel(channel);
        for (int pixel = 0; pixel < 256; pixel++)
            values[pixel] = float((pixel * 7 + channel * 31) % 256) / 255.f;
    }
    ncnn::Mat output;
    ncnn::Extractor extractor = net.create_extractor();
    if (extractor.input("data", input) != 0 || extractor.extract("output", output) != 0)
        return 7;
    if (output.empty() || output.w != 16 * scale || output.h != 16 * scale || output.c != 3)
        return 8;
    double checksum = 0;
    for (int channel = 0; channel < output.c; channel++)
    {
        const float* values = output.channel(channel);
        for (int pixel = 0; pixel < output.w * output.h; pixel++)
        {
            if (!std::isfinite(values[pixel]))
                return 9;
            checksum += values[pixel];
        }
    }
    if (!std::isfinite(checksum) || checksum <= 0)
        return 10;
    printf("CPU_MODEL_SMOKE_OK width=%d height=%d channels=%d checksum=%.9f vulkan_created=false\n",
           output.w, output.h, output.c, checksum);
    return 0;
}
