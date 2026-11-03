#pragma once
#include <cstdint>
#include <fstream>
#include <sstream>
#include <string>

namespace transcriptor {
inline bool model_fits_memory(uint64_t weights, uint64_t total, uint64_t available, bool gpu, uint64_t working_reserve = 0) {
    const uint64_t reserve = working_reserve ? working_reserve : (gpu ? 1024ull : 512ull) * 1024 * 1024;
    return (!total || weights <= total * 55 / 100) &&
        (!available || (available >= reserve && weights <= available - reserve));
}

// Quantization shrinks weights, not the encoder graph. Keep an architecture-based
// working allowance instead of treating a quantized large model as a small one.
inline uint64_t whisper_working_reserve(int audio_state, bool gpu) {
    const uint64_t mib = 1024ull * 1024;
    if (audio_state >= 1280) return (gpu ? 2048ull : 1536ull) * mib;
    if (audio_state >= 1024) return (gpu ? 1792ull : 1280ull) * mib;
    if (audio_state >= 768) return (gpu ? 1280ull : 768ull) * mib;
    return (gpu ? 1024ull : 512ull) * mib;
}

inline int whisper_audio_state(const std::string & path) {
    std::ifstream file(path, std::ios::binary);
    unsigned char header[16]{};
    if (!file.read(reinterpret_cast<char *>(header), sizeof(header))) return 0;
    auto read = [&](int offset) { return uint32_t(header[offset]) | uint32_t(header[offset+1]) << 8 |
        uint32_t(header[offset+2]) << 16 | uint32_t(header[offset+3]) << 24; };
    return read(0) == 0x67676d6c ? static_cast<int>(read(12)) : 0;
}

struct MemoryInfo { uint64_t total = 0; uint64_t available = 0; };
inline MemoryInfo read_memory_info() {
    MemoryInfo result;
    std::ifstream input("/proc/meminfo");
    std::string line;
    while (std::getline(input, line)) {
        std::istringstream fields(line);
        std::string key;
        uint64_t kib = 0;
        if (!(fields >> key >> kib)) continue;
        if (key == "MemTotal:") result.total = kib * 1024;
        if (key == "MemAvailable:") result.available = kib * 1024;
        if (result.total && result.available) break;
    }
    return result;
}
}
