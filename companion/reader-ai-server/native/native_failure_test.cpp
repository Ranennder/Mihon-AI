#include "mihon_runtime_checks.h"
#include <cstring>

// A CI subprocess checks the same fail-fast routine inserted into native GPU
// submission/model code, without loading a graphics driver.
int main(int argc, char** argv)
{
    if (argc == 2 && std::strcmp(argv[1], "success") == 0)
    {
        mihon_checked(0, "success");
        return 0;
    }
    mihon_checked(-4, "injected submit failure");
    // This marker must never be emitted after a failed operation.
    std::fprintf(stderr, "UNREACHABLE_AFTER_GPU_FAULT\n");
    return 99;
}
