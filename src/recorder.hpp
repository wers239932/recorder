#pragma once

#include <cstdint>
#include <string>
#include "esp_err.h"

class Recorder {
public:
    struct Config {
        std::string mount_point = "/sdcard";
        std::string dir = "/sdcard/rec";
        uint32_t sample_rate = 16000;
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

    // Task function that pulls from I2S and writes to SD (call from a task)
    static void task_run(void* arg);
};
