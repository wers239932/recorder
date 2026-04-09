#pragma once

#include <string>
#include <cstddef>

extern "C" {
#include "esp_err.h"
}

class HttpUploader {
public:
    enum class Phase {
        IDLE = 0,
        PREPARING,
        CONNECTING,
        SENDING_HEADERS,
        SENDING_BODY,
        WAITING_RESPONSE,
        SUCCESS,
        FAILED
    };

    struct Status {
        Phase phase = Phase::IDLE;
        int http_code = 0;
        size_t bytes_sent = 0;
        size_t total_bytes = 0;
        int last_errno = 0;
    };

    // Start background upload of a WAV file to the given URL (e.g. "http://192.168.0.5:8080/upload")
    // The task runs in background and updates internal Status; query with get_status().
    static esp_err_t start_wav_upload(const char* url, const char* wav_path);

    // Get current status snapshot
    static Status get_status();

private:
    static void upload_task(void* arg);
};