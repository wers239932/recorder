#pragma once

#include <string>

extern "C" {
#include "esp_err.h"
}

/**
 * Simple HTTP uploader that posts a fixed text payload to a fixed URL
 * in a background FreeRTOS task. While uploading, Recorder::state is SENDING.
 * On completion (success or failure), state is set back to READY.
 */
class HttpUploader {
public:
    // Starts background upload of fixed text to the given URL (e.g. "http://192.168.0.5:8080/")
    // Returns ESP_OK if the upload task has been started, error otherwise.
    static esp_err_t start_fixed_text_upload(const char* url);

private:
    static void upload_task(void* arg);
};