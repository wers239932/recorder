#include "http_uploader.hpp"
#include "recorder.hpp"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <errno.h>

extern "C" {
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_err.h"
#include "esp_wifi.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "lwip/sockets.h"
#include "lwip/netdb.h"
#include "arpa/inet.h"
}

static const char* TAG_HTTP = "HttpUploader";
static TaskHandle_t s_http_task = nullptr;

esp_err_t HttpUploader::start_fixed_text_upload(const char* url) {
    if (s_http_task != nullptr) {
        printf("%s: task already running\n", TAG_HTTP);
        return ESP_ERR_INVALID_STATE;
    }
    // URL игнорируется в raw-режиме, но оставляем для совместимости сигнатуры
    BaseType_t ok = xTaskCreate(HttpUploader::upload_task, "http_up", 4096, nullptr, 5, &s_http_task);
    if (ok != pdPASS) {
        s_http_task = nullptr;
        return ESP_FAIL;
    }
    return ESP_OK;
}

void HttpUploader::upload_task(void* arg) {
    // 🔥 ВСЕ переменные в начале — правило C++ для goto
    const char* payload = "test-upload: hello from ESP32\n";
    const char* server_ip = "192.168.31.68";
    const uint16_t server_port = 8080;
    
    int sock = -1;
    int http_status = 0;
    bool success = false;
    char req_buf[512] = {0};
    char resp_buf[512] = {0};
    int req_len = 0;
    int sent = 0;
    int recv_len = 0;
    
    wifi_ap_record_t ap_info = {};
    wifi_mode_t mode = WIFI_MODE_NULL;
    esp_err_t wifi_info = ESP_FAIL;
    esp_netif_ip_info_t ip_info = {};
    esp_netif_t* netif = nullptr;
    struct sockaddr_in dest = {};
    struct timeval tv = {};

    // 1. Проверка WiFi
    esp_wifi_get_mode(&mode);
    wifi_info = esp_wifi_sta_get_ap_info(&ap_info);
    if (mode != WIFI_MODE_STA || wifi_info != ESP_OK) {
        ESP_LOGE(TAG_HTTP, "WiFi not ready. Aborting.");
        goto cleanup;
    }
    ESP_LOGI(TAG_HTTP, "WiFi OK. RSSI: %d dBm", ap_info.rssi);

    netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    if (esp_netif_get_ip_info(netif, &ip_info) == ESP_OK) {
        ESP_LOGI(TAG_HTTP, "ESP IP: " IPSTR, IP2STR(&ip_info.ip));
    }
    Recorder::state = Recorder::SENDING;

    // 2. Создаём сокет и подключаемся
    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (sock < 0) {
        ESP_LOGE(TAG_HTTP, "Socket creation failed");
        goto cleanup;
    }

    tv.tv_sec = 5; tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    dest.sin_family = AF_INET;
    dest.sin_port = htons(server_port);
    inet_pton(AF_INET, server_ip, &dest.sin_addr);

    if (connect(sock, (struct sockaddr*)&dest, sizeof(dest)) != 0) {
        ESP_LOGE(TAG_HTTP, "TCP connect failed (errno=%d)", errno);
        goto cleanup;
    }
    ESP_LOGI(TAG_HTTP, "✅ TCP connected to %s:%d", server_ip, server_port);

    // 3. Формируем HTTP/1.1 POST запрос вручную
    req_len = snprintf(req_buf, sizeof(req_buf),
        "POST / HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Content-Type: text/plain\r\n"
        "Content-Length: %d\r\n"
        "Connection: close\r\n"
        "\r\n"
        "%s",
        server_ip, server_port, (int)strlen(payload), payload);

    // 4. Отправляем
    sent = 0;
    while (sent < req_len) {
        int n = send(sock, req_buf + sent, req_len - sent, 0);
        if (n <= 0) {
            ESP_LOGE(TAG_HTTP, "Send failed (errno=%d)", errno);
            goto cleanup;
        }
        sent += n;
    }
    ESP_LOGI(TAG_HTTP, "✅ Sent %d bytes HTTP request", req_len);

    // 5. Читаем ответ
    recv_len = recv(sock, resp_buf, sizeof(resp_buf) - 1, 0);
    if (recv_len > 0) {
        resp_buf[recv_len] = '\0';
        // Парсим статус: "HTTP/1.1 200 OK"
        if (sscanf(resp_buf, "HTTP/%*d.%*d %d", &http_status) == 1) {
            ESP_LOGI(TAG_HTTP, "📥 Server response: HTTP %d", http_status);
            success = (http_status >= 200 && http_status < 300);
        } else {
            ESP_LOGW(TAG_HTTP, "⚠️ Unexpected response format: %.50s...", resp_buf);
            success = false;
        }
    } else {
        ESP_LOGE(TAG_HTTP, "No response received (errno=%d)", errno);
    }

    if (success) ESP_LOGI(TAG_HTTP, "✅ UPLOAD SUCCESS");
    else ESP_LOGE(TAG_HTTP, "❌ UPLOAD FAILED (HTTP %d)", http_status);

cleanup:
    if (sock >= 0) {
        close(sock);
        sock = -1;
    }
    if (arg) free(arg); // на случай если передали URL
    s_http_task = nullptr;
    Recorder::state = Recorder::READY;
    vTaskDelete(nullptr);
}