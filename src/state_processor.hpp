#include "state_processor.hpp"
#include <cstdio>
#include "esp_timer.h"

static const char* TAG = "StateProcessor";

StateProcessor::StateProcessor(const Config& cfg)
    : config_(cfg), 
      last_state_(Recorder::WAITING_FOR_CREDS),
      last_process_time_ms_(0) {
    printf("%s: initialized with interval %lu ms\n", TAG, config_.process_interval_ms);
}

StateProcessor::~StateProcessor() {
}

uint32_t StateProcessor::get_time_ms() {
    return (uint32_t)(esp_timer_get_time() / 1000);
}

bool StateProcessor::should_process() {
    uint32_t now = get_time_ms();
    if (now - last_process_time_ms_ >= config_.process_interval_ms) {
        last_process_time_ms_ = now;
        return true;
    }
    return false;
}

void StateProcessor::process() {
    if (!should_process()) {
        return;
    }

    Recorder::State current_state = Recorder::state;

    // Log state transitions
    if (current_state != last_state_) {
        printf("%s: state transition %d -> %d\n", TAG, last_state_, current_state);
        last_state_ = current_state;
    }

    // Process based on current state
    switch (current_state) {
        case Recorder::WAITING_FOR_CREDS:
            process_waiting_for_creds();
            break;
        
        case Recorder::READY:
            process_ready();
            break;
        
        case Recorder::RECORDING:
            process_recording();
            break;
        
        case Recorder::SENDING:
            process_sending();
            break;
        
        default:
            printf("%s: unknown state %d\n", TAG, current_state);
            break;
    }
}

void StateProcessor::process_waiting_for_creds() {
    // Handle waiting for credentials state
    // e.g., check if credentials are available, WiFi connected, etc.
    printf("%s: processing WAITING_FOR_CREDS state\n", TAG);
}

void StateProcessor::process_ready() {
    // Handle ready state
    // e.g., check for start recording signal from button handler
    printf("%s: processing READY state\n", TAG);
}

void StateProcessor::process_recording() {
    // Handle recording state
    // e.g., monitor recording progress, check for stop signal
    if (Recorder::is_recording()) {
        printf("%s: processing RECORDING state - recording in progress\n", TAG);
    } else {
        printf("%s: processing RECORDING state - recording stopped unexpectedly\n", TAG);
    }
}

void StateProcessor::process_sending() {
    // Handle sending state
    // e.g., monitor upload progress, handle completion
    printf("%s: processing SENDING state\n", TAG);
}
</parameter>
</invoke>
```

Теперь обновим main.cpp, чтобы использовать StateProcessor:
```tool
TOOL_NAME: read_file
BEGIN_ARG: filepath
src/main.cpp