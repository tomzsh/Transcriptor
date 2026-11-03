#include "../../main/cpp/inference_memory.h"
#include <cassert>

int main() {
    constexpr uint64_t mib = 1024ull * 1024;
    using transcriptor::model_fits_memory;
    assert(model_fits_memory(512*mib, 8192*mib, 2048*mib, true));
    assert(!model_fits_memory(3072*mib, 8192*mib, 3584*mib, true));
    assert(model_fits_memory(3072*mib, 8192*mib, 3584*mib, false));
    assert(!model_fits_memory(3072*mib, 4096*mib, 4096*mib, false));
    assert(!model_fits_memory(3072*mib, 8192*mib, 3328*mib, false));
    using transcriptor::whisper_working_reserve;
    assert(!model_fits_memory(548*mib, 8192*mib, 2048*mib, false, whisper_working_reserve(1280, false)));
    assert(model_fits_memory(548*mib, 8192*mib, 3072*mib, false, whisper_working_reserve(1280, false)));
    assert(!model_fits_memory(182*mib, 8192*mib, 900*mib, false, whisper_working_reserve(768, false)));
    assert(model_fits_memory(182*mib, 8192*mib, 2048*mib, true, whisper_working_reserve(768, true)));
}
