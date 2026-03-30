#include "http_uploader.hpp"
#include "recorder.hpp"

#include <cstring>
#include <cstdlib>

extern "C" {
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_http_client.h"
#include "esp_err.h"
}

static const char* TAG_HTTP = "HttpUploader";
static TaskHandle_t s_http_task = nullptr;

esp_err_t HttpUploader::start_fixed_text_upload(const char* url) {
    if (s_http_task != nullptr) {
        printf("%s: task already running\n", TAG_HTTP);
        return ESP_ERR_INVALID_STATE;
    }
    const char* kDefaultUrl = "http://192.168.0.5:8080/";
    const char* src = (url && url[0]) ? url : kDefaultUrl;
    char* url_copy = (char*)malloc(strlen(src) + 1);
    if (!url_copy) return ESP_ERR_NO_MEM;
    strcpy(url_copy, src);

    BaseType_t ok = xTaskCreate(HttpUploader::upload_task, "http_up", 4096, url_copy, 5, &s_http_task);
    if (ok != pdPASS) {
        free(url_copy);
        s_http_task = nullptr;
        return ESP_FAIL;
    }
    return ESP_OK;
}

void HttpUploader::upload_task(void* arg) {
    char* url = (char*)arg;
    const char* payload = "test-upload: hello from ESP32\n"; // fixed text payload for validation

    // Ensure state reflects sending while we are uploading
    Recorder::state = Recorder::SENDING;

    esp_http_client_config_t cfg = {};
    cfg.url = url;
    cfg.timeout_ms = 10000; // 10s
    cfg.method = HTTP_METHOD_POST;

    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) {
        printf("%s: client init failed\n", TAG_HTTP);
        goto cleanup;
    }

    if (esp_http_client_set_header(client, "Content-Type", "text/plain") != ESP_OK) {
        printf("%s: set_header failed\n", TAG_HTTP);
        goto done_client;
    }

    if (esp_http_client_open(client, (int)strlen(payload)) != ESP_OK) {
        printf("%s: open failed\n", TAG_HTTP);
        goto done_client;
    }

    {
        int to_write = (int)strlen(payload);
        int written = esp_http_client_write(client, payload, to_write);
        if (written != to_write) {
            printf("%s: write failed (%d/%d)\n", TAG_HTTP, written, to_write);
        } else {
            (void)esp_http_client_fetch_headers(client); // optional
            int status = esp_http_client_get_status_code(client);
            printf("%s: upload done, HTTP %d\n", TAG_HTTP, status);
        }
    }

    esp_http_client_close(client);

done_client:
    esp_http_client_cleanup(client);

cleanup:
    if (url) free(url);
    s_http_task = nullptr;
    Recorder::state = Recorder::READY; // back to READY after attempt (success/fail)
    vTaskDelete(nullptr);
}
