#pragma once

#include <cstdint>
#include <string>

extern "C" {
#include "esp_err.h"
}

class Recorder {
public:
    struct Config {
        std::string mount_point = "/sdcard";
        std::string dir = "/sdcard";
        uint32_t sample_rate = 16000;
        uint32_t i2s_sample_rate = 0;
        uint16_t bits_per_sample = 16;
        uint8_t channels = 1;
    };

    enum State {
        WAITING_FOR_CREDS,
        READY,
        RECORDING,
        SENDING
    };

    static State state;  // Current state of the recorder

    static esp_err_t init(const Config& cfg);
    static void deinit();

    // Start new WAV file. Returns ESP_OK on success.
    static esp_err_t start();
    // Stop recording and finalize WAV header.
    static void stop();
    // Returns true if recording
    static bool is_recording();

    // Return absolute path to the last completed WAV file. Returns true on success.
    static bool get_last_wav_path(std::string& out_path);

    // Task function that pulls from I2S and writes to SD (call from a task)
    static void task_run(void* arg);
};
