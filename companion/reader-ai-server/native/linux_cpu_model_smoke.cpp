#include "net.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>

// CPU reference validates the exact pinned model pair. The separate CLI smoke
// exercises Real-ESRGAN's Vulkan preprocessing, inference and postprocessing.
int main(int argc, char** argv)
{
    if (argc != 4 && argc != 5)
        return 2;
    const int scale = std::atoi(argv[3]);
    if (scale < 2 || scale > 4)
        return 2;
    ncnn::Net net;
    net.opt.use_vulkan_compute = false;
    net.opt.num_threads = 1;
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_arithmetic = false;
    if (net.load_param(argv[1]) != 0)
        return 4;
    if (net.load_model(argv[2]) != 0)
        return 6;
    const bool reference = argc == 5;
    const int width = reference ? 36 : 16;
    ncnn::Mat input(width, width, 3);
    for (int channel = 0; channel < 3; channel++)
    {
        float* values = input.channel(channel);
        for (int pixel = 0; pixel < width * width; pixel++)
        {
            if (!reference)
                values[pixel] = float((pixel * 7 + channel * 31) % 256) / 255.f;
            else
            {
                // Match realesrgan_preproc.comp's reflect padding exactly for
                // a single 16x16 tile and the CLI's ten-pixel prepadding.
                const int x = 15 - std::abs(std::abs(pixel % width - 10) - 15);
                const int y = 15 - std::abs(std::abs(pixel / width - 10) - 15);
                values[pixel] = float((x * 17 + y * 11 + channel * 53) % 256) / 255.f;
            }
        }
    }
    ncnn::Mat output;
    ncnn::Extractor extractor = net.create_extractor();
    if (extractor.input("data", input) != 0 || extractor.extract("output", output) != 0)
        return 7;
    if (output.empty() || output.w != width * scale || output.h != width * scale || output.c != 3)
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
    if (reference)
    {
        FILE* image = std::fopen(argv[4], "wb");
        if (!image)
            return 11;
        std::fprintf(image, "P6\n%d %d\n255\n", 16 * scale, 16 * scale);
        for (int y = 0; y < 16 * scale; y++)
            for (int x = 0; x < 16 * scale; x++)
                for (int channel = 0; channel < 3; channel++)
                {
                    const float* values = output.channel(channel);
                    const float value = values[(y + 10 * scale) * output.w + x + 10 * scale];
                    const int quantized = std::max(0, std::min(255, int(std::floor(value * 255.f + 0.5f))));
                    if (std::fputc(quantized, image) == EOF)
                    {
                        std::fclose(image);
                        return 12;
                    }
                }
        if (std::fclose(image) != 0)
            return 12;
    }
    std::printf("CPU_MODEL_SMOKE_OK width=%d height=%d channels=%d checksum=%.9f vulkan_created=false\n",
                output.w, output.h, output.c, checksum);
    return 0;
}
