#pragma once

#include <cstdint>
#include "esp_err.h"

class I2SInput {
public:
    struct Config {
        int pin_bclk;
        int pin_ws;
        int pin_din;
        int sample_rate = 16000;   // 16 kHz default
        int bits_per_sample = 16;  // 16-bit signed PCM
        bool use_pdm = false;      // for future
    };

    static esp_err_t init(const Config& cfg);
    static void deinit();

    // Read raw samples into buffer. For now, if no mic attached, will read floating noise (near zeros).
    // Returns number of bytes read (multiple of frame bytes) or negative on error.
    static int read(void* buffer, size_t bytes, uint32_t timeout_ms = 50);
};
