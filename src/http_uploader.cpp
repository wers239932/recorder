#include "http_uploader.hpp"
#include "auth/auth_manager.h"
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
static AuthManager* s_auth_manager = nullptr;

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
void HttpUploader::set_auth_manager(AuthManager* auth_manager) { s_auth_manager = auth_manager; }

static bool upload_once(const std::string& url,
                        const std::string& wav_path,
                        const std::string& bearer_token,
                        bool& unauthorized) {
    int sock = -1;
    FILE* f = nullptr;
    char* hdr_buf = nullptr;
    char resp_buf[512] = {0};
    uint8_t* buf = nullptr;
    int sent = 0;
    int hdr_len = 0;
    int gairet = 0;
    int http_status = 0;
    int rcv = 0;
    bool success = false;
    unauthorized = false;
    struct addrinfo* res = nullptr;
    struct addrinfo hints = {};
    uint16_t port = 80;
    std::string host;
    std::string path;
    std::string auth_header;
    struct stat st = {};
    struct timeval tv = {};

    set_phase(HttpUploader::Phase::PREPARING);
    s_status.bytes_sent = 0;
    s_status.total_bytes = 0;
    s_status.http_code = 0;
    s_status.last_errno = 0;

    if (stat(wav_path.c_str(), &st) != 0) {
        ESP_LOGE(TAG_HTTP, "stat failed for %s (errno=%d)", wav_path.c_str(), errno);
        goto cleanup;
    }
    s_status.total_bytes = static_cast<size_t>(st.st_size);

    f = fopen(wav_path.c_str(), "rb");
    if (!f) {
        ESP_LOGE(TAG_HTTP, "fopen failed for %s (errno=%d)", wav_path.c_str(), errno);
        goto cleanup;
    }

    if (!parse_url(url, host, port, path)) {
        ESP_LOGE(TAG_HTTP, "URL parse failed: %s", url.c_str());
        goto cleanup;
    }

    set_phase(HttpUploader::Phase::CONNECTING);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char portstr[8];
    std::snprintf(portstr, sizeof(portstr), "%u", static_cast<unsigned>(port));

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

    tv.tv_sec = 5;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    if (connect(sock, res->ai_addr, res->ai_addrlen) != 0) {
        s_status.last_errno = errno;
        ESP_LOGE(TAG_HTTP, "connect failed (errno=%d)", errno);
        goto cleanup;
    }
    freeaddrinfo(res);
    res = nullptr;
    set_phase(HttpUploader::Phase::SENDING_HEADERS);
    auth_header = bearer_token.empty()
        ? std::string()
        : std::string("Authorization: Bearer ") + bearer_token + "\r\n";
    hdr_len = std::snprintf(nullptr, 0,

        "POST %s HTTP/1.1\r\n"
        "Host: %s:%u\r\n"
        "Content-Type: audio/wav\r\n"
        "Content-Length: %u\r\n"

        "%s"
        "Connection: close\r\n"
        "\r\n",
        path.c_str(), host.c_str(), static_cast<unsigned>(port), static_cast<unsigned>(s_status.total_bytes), auth_header.c_str());
    if (hdr_len <= 0) {
        goto cleanup;
    }
    hdr_buf = static_cast<char*>(malloc(static_cast<size_t>(hdr_len) + 1));
    if (!hdr_buf) {
        goto cleanup;
    }
    std::snprintf(hdr_buf, static_cast<size_t>(hdr_len) + 1,
        "POST %s HTTP/1.1\r\n"
        "Host: %s:%u\r\n"
        "Content-Type: audio/wav\r\n"
        "Content-Length: %u\r\n"
        "%s"
        "Connection: close\r\n"
        "\r\n",
        path.c_str(), host.c_str(), static_cast<unsigned>(port), static_cast<unsigned>(s_status.total_bytes), auth_header.c_str());


    sent = 0;
    while (sent < hdr_len) {
        const int n = send(sock, hdr_buf + sent, hdr_len - sent, 0);
        if (n <= 0) {
            s_status.last_errno = errno;
            goto cleanup;
        }
        sent += n;
    }

    set_phase(HttpUploader::Phase::SENDING_BODY);
    static const size_t CHUNK = 2048;
    buf = static_cast<uint8_t*>(malloc(CHUNK));
    if (!buf) {
        goto cleanup;
    }

    while (s_status.bytes_sent < s_status.total_bytes) {
        size_t to_read = s_status.total_bytes - s_status.bytes_sent;
        if (to_read > CHUNK) {
            to_read = CHUNK;
        }
        const size_t rd = fread(buf, 1, to_read, f);
        if (rd == 0) {
            break;
        }
        size_t off = 0;
        while (off < rd) {
            const int n = send(sock, reinterpret_cast<const char*>(buf) + off, rd - off, 0);
            if (n <= 0) {
                s_status.last_errno = errno;
                goto cleanup;
            }
            off += static_cast<size_t>(n);
            s_status.bytes_sent += static_cast<size_t>(n);
        }
    }
    if (s_status.bytes_sent != s_status.total_bytes) {
        ESP_LOGE(TAG_HTTP, "short send: %u/%u",
                 static_cast<unsigned>(s_status.bytes_sent),
                 static_cast<unsigned>(s_status.total_bytes));
        goto cleanup;
    }

    free(buf);
    buf = nullptr;
    fclose(f);
    f = nullptr;

    set_phase(HttpUploader::Phase::WAITING_RESPONSE);
    rcv = recv(sock, resp_buf, sizeof(resp_buf) - 1, 0);
    if (rcv > 0) {
        resp_buf[rcv] = '\0';
        if (std::sscanf(resp_buf, "HTTP/%*d.%*d %d", &http_status) == 1) {
            s_status.http_code = http_status;
            success = (http_status >= 200 && http_status < 300);
            unauthorized = (http_status == 401 || http_status == 403);
        }
    }

cleanup:
    if (res) {
        freeaddrinfo(res);
    }
    if (buf) {
        free(buf);
    }
    if (f) {
        fclose(f);
    }
    if (hdr_buf) {
        free(hdr_buf);
    }
    if (sock >= 0) {
        close(sock);
    }
    return success;
}

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
    bool success = false;
    wifi_ap_record_t ap_info = {};
    wifi_mode_t mode = WIFI_MODE_NULL;
    esp_err_t wifi_info = ESP_FAIL;
    esp_netif_ip_info_t ip_info = {};
    esp_netif_t* netif = nullptr;
    bool unauthorized = false;
    std::string token;

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

    if (s_auth_manager && s_auth_manager->hasConfig()) {
        token = s_auth_manager->getToken();
        if (token.empty()) {
            ESP_LOGE(TAG_HTTP, "No auth token available; upload deferred");
            s_status.http_code = 401;
            set_phase(Phase::FAILED);
            goto cleanup;
        }
    }

    success = upload_once(s_url, s_wav_path, token, unauthorized);
    if (!success && unauthorized && s_auth_manager) {
        ESP_LOGW(TAG_HTTP, "Upload rejected with HTTP %d, refreshing token", s_status.http_code);
        if (s_auth_manager->refreshToken()) {
            token = s_auth_manager->getToken();
            unauthorized = false;
            success = upload_once(s_url, s_wav_path, token, unauthorized);
        } else {
            ESP_LOGE(TAG_HTTP, "Token refresh failed after unauthorized response");
        }
    } else if (!success && s_status.http_code != 0) {
        ESP_LOGE(TAG_HTTP, "Upload failed with HTTP %d", s_status.http_code);
    }

cleanup:
    if (success) {
        set_phase(Phase::SUCCESS);
    } else {
        set_phase(Phase::FAILED);
        if (s_status.http_code == 401 || s_status.http_code == 403) {
            ESP_LOGE(TAG_HTTP, "Upload remains unauthorized after retry; keeping WAV on SD");
        } else {
            ESP_LOGE(TAG_HTTP, "Upload failed; WAV kept on SD for later retry");
        }
    }

    s_http_task = nullptr;
    Recorder::state = Recorder::READY;
    vTaskDelete(nullptr);
}
