#pragma once

#include "recorder.hpp"
#include <cstdint>
#include <string>

class StateProcessor {
public:
    struct Config {
        uint32_t process_interval_ms = 100;  // How often to check state (ms)
    };

    explicit StateProcessor(const Config& cfg);
    ~StateProcessor();

    // Main processing function - call this in main loop
    void process();

    // Get current processing interval
    uint32_t get_interval_ms() const { return config_.process_interval_ms; }

private:
    Config config_;
    Recorder::State last_state_;
    uint32_t last_process_time_ms_;

    // Helper to get current time in milliseconds
    uint32_t get_time_ms();

    // Check if enough time has passed since last process
    bool should_process();

    // State-specific handlers
    void process_waiting_for_creds();
    void process_ready();
    void process_recording();
    void process_sending();