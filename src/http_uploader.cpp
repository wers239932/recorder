#include "http_uploader.hpp"
#include "recorder.hpp"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <sys/stat.h>
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
static HttpUploader::Status s_status;
static std::string s_url;
static std::string s_wav_path;

static inline void set_phase(HttpUploader::Phase p) { s_status.phase = p; }

static bool parse_url(const std::string& url, std::string& host, uint16_t& port, std::string& path) {
    std::string rest = url;
    const char* scheme = "http://";
    if (rest.rfind(scheme, 0) == 0) rest = rest.substr(strlen(scheme));
    auto slash = rest.find('/');
    std::string hostport = (slash == std::string::npos) ? rest : rest.substr(0, slash);
    path = (slash == std::string::npos) ? "/" : rest.substr(slash);
    auto colon = hostport.find(':');
    if (colon == std::string::npos) {
        host = hostport;
        port = 80;
    } else {
        host = hostport.substr(0, colon);
        port = (uint16_t)atoi(hostport.substr(colon + 1).c_str());
        if (port == 0) port = 80;
    }
    return !host.empty();
}

HttpUploader::Status HttpUploader::get_status() { return s_status; }

esp_err_t HttpUploader::start_wav_upload(const char* url, const char* wav_path) {
    if (s_http_task != nullptr) {
        printf("%s: task already running\n", TAG_HTTP);
        return ESP_ERR_INVALID_STATE;
    }
    if (!url || !wav_path) return ESP_ERR_INVALID_ARG;
    s_url = url;
    s_wav_path = wav_path;
    s_status = {};
    set_phase(Phase::PREPARING);
    BaseType_t ok = xTaskCreate(HttpUploader::upload_task, "http_up", 6144, nullptr, 5, &s_http_task);
    if (ok != pdPASS) {
        s_http_task = nullptr;
        set_phase(Phase::FAILED);
        return ESP_FAIL;
    }
    return ESP_OK;
}
void HttpUploader::upload_task(void* arg) {
    // === ВСЕ переменные объявляем ЗДЕСЬ, в начале ===
    int sock = -1;
    FILE* f = nullptr;
    int http_status = 0;
    bool success = false;
    char hdr_buf[384] = {0};
    char resp_buf[512] = {0};
    int rcv = 0;                    // ← вынесено вверх
    uint8_t* buf = nullptr;         // ← вынесено вверх
    int sent = 0;                   // ← вынесено вверх
    int hdr_len = 0;                // ← вынесено вверх
    int gairet = 0;                 // ← вынесено вверх
    struct addrinfo* res = nullptr; // ← вынесено вверх
    struct addrinfo hints = {};     // ← вынесено вверх
    uint16_t port = 80;             // ← вынесено вверх
    std::string host, path;         // ← вынесено вверх (важно!)
    struct stat st = {};            // ← вынесено вверх
    
    wifi_ap_record_t ap_info = {};
    wifi_mode_t mode = WIFI_MODE_NULL;
    esp_err_t wifi_info = ESP_FAIL;
    esp_netif_ip_info_t ip_info = {};
    esp_netif_t* netif = nullptr;
    struct timeval tv = {};

    // WiFi check
    esp_wifi_get_mode(&mode);
    wifi_info = esp_wifi_sta_get_ap_info(&ap_info);
    if (mode != WIFI_MODE_STA || wifi_info != ESP_OK) {
        ESP_LOGE(TAG_HTTP, "WiFi not ready. Aborting.");
        goto cleanup;
    }
    netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    if (esp_netif_get_ip_info(netif, &ip_info) == ESP_OK) {
        ESP_LOGI(TAG_HTTP, "ESP IP: " IPSTR, IP2STR(&ip_info.ip));
    }

    set_phase(Phase::PREPARING);

    // File size
    if (stat(s_wav_path.c_str(), &st) != 0) {
        ESP_LOGE(TAG_HTTP, "stat failed for %s (errno=%d)", s_wav_path.c_str(), errno);
        goto cleanup;
    }
    s_status.total_bytes = (size_t)st.st_size;
    s_status.bytes_sent = 0;

    f = fopen(s_wav_path.c_str(), "rb");
    if (!f) {
        ESP_LOGE(TAG_HTTP, "fopen failed for %s (errno=%d)", s_wav_path.c_str(), errno);
        goto cleanup;
    }

    // URL parse
    port = 80;
    host.clear(); path.clear();
    if (!parse_url(s_url, host, port, path)) {
        ESP_LOGE(TAG_HTTP, "URL parse failed: %s", s_url.c_str());
        goto cleanup;
    }

    // Resolve and connect
    set_phase(Phase::CONNECTING);

    hints = {};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char portstr[8];
    snprintf(portstr, sizeof(portstr), "%u", (unsigned)port);

    res = nullptr;
    gairet = getaddrinfo(host.c_str(), portstr, &hints, &res);
    if (gairet != 0 || !res) {
        ESP_LOGE(TAG_HTTP, "getaddrinfo failed: %d", gairet);
        goto cleanup;
    }

    sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        ESP_LOGE(TAG_HTTP, "socket() failed");
        goto cleanup;
    }

    tv.tv_sec = 5; tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    if (connect(sock, res->ai_addr, res->ai_addrlen) != 0) {
        s_status.last_errno = errno;
        ESP_LOGE(TAG_HTTP, "connect failed (errno=%d)", errno);
        goto cleanup;
    }
    freeaddrinfo(res);
    res = nullptr;

    // Headers
    set_phase(Phase::SENDING_HEADERS);
    hdr_len = snprintf(hdr_buf, sizeof(hdr_buf),
        "POST %s HTTP/1.1\r\n"
        "Host: %s:%u\r\n"
        "Content-Type: audio/wav\r\n"
        "Content-Length: %u\r\n"
        "Connection: close\r\n"
        "\r\n",
        path.c_str(), host.c_str(), (unsigned)port, (unsigned)s_status.total_bytes);

    sent = 0;
    while (sent < hdr_len) {
        int n = send(sock, hdr_buf + sent, hdr_len - sent, 0);
        if (n <= 0) { s_status.last_errno = errno; goto cleanup; }
        sent += n;
    }

    // Body
    set_phase(Phase::SENDING_BODY);
    static const size_t CHUNK = 2048;
    buf = (uint8_t*)malloc(CHUNK);
    if (!buf) goto cleanup;

    while (s_status.bytes_sent < s_status.total_bytes) {
        size_t to_read = s_status.total_bytes - s_status.bytes_sent;
        if (to_read > CHUNK) to_read = CHUNK;
        size_t rd = fread(buf, 1, to_read, f);
        if (rd == 0) break;
        size_t off = 0;
        while (off < rd) {
            int n = send(sock, (const char*)buf + off, rd - off, 0);
            if (n <= 0) { s_status.last_errno = errno; goto cleanup; }
            off += (size_t)n;
            s_status.bytes_sent += (size_t)n;
        }
    }
    if (s_status.bytes_sent != s_status.total_bytes) {
        ESP_LOGE(TAG_HTTP, "short send: %u/%u", (unsigned)s_status.bytes_sent, (unsigned)s_status.total_bytes);
        goto cleanup;
    }
    free(buf);
    buf = nullptr; // на всякий случай

    // Response
    set_phase(Phase::WAITING_RESPONSE);
    rcv = recv(sock, resp_buf, sizeof(resp_buf) - 1, 0);
    if (rcv > 0) {
        resp_buf[rcv] = '\0';
        if (sscanf(resp_buf, "HTTP/%*d.%*d %d", &http_status) == 1) {
            s_status.http_code = http_status;
            success = (http_status >= 200 && http_status < 300);
        }
    }

cleanup:
    if (res) { freeaddrinfo(res); res = nullptr; }
    if (buf) { free(buf); buf = nullptr; }
    if (f) { fclose(f); f = nullptr; }
    if (sock >= 0) { close(sock); sock = -1; }

    if (success) set_phase(Phase::SUCCESS); else set_phase(Phase::FAILED);

    s_http_task = nullptr;
    Recorder::state = Recorder::READY;
    vTaskDelete(nullptr);
}
