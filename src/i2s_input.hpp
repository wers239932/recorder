#pragma once

#include <cstddef>
#include <cstdint>

extern "C" {
#include "esp_err.h"
}

class I2SInput {
public:
    struct Config {
        int pin_bclk;
        int pin_ws;
        int pin_din;
        int sample_rate = 16000;   // 16 kHz default
        int bits_per_sample = 16;  // 16-bit signed PCM
        bool mic_is_left = true;   // INMP441 L/R -> GND => data in left slot
    };

    static constexpr size_t kRawSampleBytes = sizeof(int32_t);

    static esp_err_t init(const Config& cfg);
    static void deinit();

    // Read raw samples into buffer. For now, if no mic attached, will read floating noise (near zeros).
    // Returns number of bytes read (multiple of frame bytes) or negative on error.
    static int read(void* buffer, size_t bytes, uint32_t timeout_ms = 50);

    static int16_t raw_to_pcm16(int32_t raw_sample);
};
