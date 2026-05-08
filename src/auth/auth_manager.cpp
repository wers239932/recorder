#include "auth/auth_manager.h"

#include <cstdio>
#include <cstring>
#include <string>

extern "C" {
#include "esp_err.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "cJSON.h"
#include "mbedtls/sha256.h"
}

static const char* TAG_AUTH = "AUTH";

#ifndef LOG_ERROR
#define LOG_ERROR(fmt, ...) ESP_LOGE(TAG_AUTH, fmt, ##__VA_ARGS__)
#endif

namespace {
struct HttpResponseBuffer {
    std::string body;
};

const cJSON* unwrap_api_response_data(const cJSON* root) {
    if (!root || !cJSON_IsObject(root)) {
        return nullptr;
    }

    const cJSON* data = cJSON_GetObjectItemCaseSensitive(root, "data");
    return cJSON_IsObject(data) ? data : root;
}

esp_err_t http_event_handler(esp_http_client_event_t* evt) {
    if (!evt || !evt->user_data) {
        return ESP_OK;
    }

    auto* response = static_cast<HttpResponseBuffer*>(evt->user_data);
    if (evt->event_id == HTTP_EVENT_ON_DATA && evt->data && evt->data_len > 0) {
        response->body.append(static_cast<const char*>(evt->data), evt->data_len);
    }

    return ESP_OK;
}
}  // namespace

uint64_t AuthManager::now_ms() {
    return static_cast<uint64_t>(esp_timer_get_time() / 1000);
}

std::string AuthManager::trim(const std::string& value) {
    size_t start = 0;
    size_t end = value.size();
    while (start < end && (value[start] == ' ' || value[start] == '\t' || value[start] == '\r' || value[start] == '\n')) {
        ++start;
    }
    while (end > start && (value[end - 1] == ' ' || value[end - 1] == '\t' || value[end - 1] == '\r' || value[end - 1] == '\n')) {
        --end;
    }
    return value.substr(start, end - start);
}

std::string AuthManager::normalize_url(const std::string& value, const char* field_name) {
    std::string url = trim(value);
    if (url.empty()) {
        return url;
    }

    if (url.rfind("https://", 0) == 0) {
        std::string downgraded = "http://" + url.substr(std::strlen("https://"));
        ESP_LOGW(TAG_AUTH, "%s uses HTTPS, but firmware only supports plain HTTP. Downgrading to %s",
                 field_name, downgraded.c_str());
        return downgraded;
    }

    if (url.rfind("http://", 0) != 0) {
        std::string with_scheme = "http://" + url;
        ESP_LOGW(TAG_AUTH, "%s has no URL scheme. Assuming %s", field_name, with_scheme.c_str());
        return with_scheme;
    }

    return url;
}

std::string AuthManager::derive_upload_url(const std::string& login_url) {
    static const char* login_suffix = "/api/v1/auth/login";
    const size_t suffix_len = std::strlen(login_suffix);
    if (login_url.size() >= suffix_len && login_url.compare(login_url.size() - suffix_len, suffix_len, login_suffix) == 0) {
        return login_url.substr(0, login_url.size() - suffix_len) + "/api/v1/data/upload";
    }
    return login_url;
}

std::string AuthManager::derive_command_url(const std::string& login_url) {
    static const char* login_suffix = "/api/v1/auth/login";
    const size_t suffix_len = std::strlen(login_suffix);
    if (login_url.size() >= suffix_len && login_url.compare(login_url.size() - suffix_len, suffix_len, login_suffix) == 0) {
        return login_url.substr(0, login_url.size() - suffix_len) + "/api/v1/device/command";
    }
    return login_url;
}

std::string AuthManager::sha256_hex(const std::string& input) {
    unsigned char hash[32] = {};
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx,
                          reinterpret_cast<const unsigned char*>(input.data()),
                          input.size());
    mbedtls_sha256_finish(&ctx, hash);
    mbedtls_sha256_free(&ctx);

    static const char* kHex = "0123456789abcdef";
    std::string hex;
    hex.resize(sizeof(hash) * 2);
    for (size_t i = 0; i < sizeof(hash); ++i) {
        hex[i * 2] = kHex[(hash[i] >> 4) & 0x0F];
        hex[i * 2 + 1] = kHex[hash[i] & 0x0F];
    }
    return hex;
}

bool AuthManager::is_token_expiring() const {
    if (token_.empty() || token_expires_at_ms_ == 0) {
        return true;
    }
    static constexpr uint64_t kRefreshBeforeExpiryMs = 60ULL * 1000ULL;
    return now_ms() + kRefreshBeforeExpiryMs >= token_expires_at_ms_;
}

bool AuthManager::load_config(const char* configPath) {
    std::string content;
    FILE* f = std::fopen(configPath, "r");
    if (!f) {
        FILE* wf = std::fopen(configPath, "w");
        if (!wf) {
            LOG_ERROR("failed to create auth config template at %s", configPath);
            return false;
        }
            const char* template_json =
                "{\n"
                "  \"login\": \"device_01\",\n"
                "  \"password\": \"pre_shared_secret_key\",\n"
                "  \"server_url\": \"http://192.168.1.100:8080/api/v1/auth/login\",\n"
                "  \"upload_url\": \"http://192.168.1.100:8080/api/v1/data/upload\",\n"
                "  \"command_url\": \"http://192.168.1.100:8080/api/v1/device/command\"\n"
                "}\n";
        const size_t expected = std::strlen(template_json);
        const size_t written = std::fwrite(template_json, 1, expected, wf);
        std::fclose(wf);
        if (written != expected) {
            LOG_ERROR("failed to write auth config template at %s", configPath);
            return false;
        }
        ESP_LOGW(TAG_AUTH, "auth config template created at %s", configPath);
        return false;
    }

    char buffer[256];
    size_t n = 0;
    while ((n = std::fread(buffer, 1, sizeof(buffer), f)) > 0) {
        content.append(buffer, n);
    }
    std::fclose(f);

    cJSON* root = cJSON_Parse(content.c_str());
    if (!root) {
        LOG_ERROR("invalid JSON in %s", configPath);
        return false;
    }

    const cJSON* login = cJSON_GetObjectItemCaseSensitive(root, "login");
    const cJSON* password = cJSON_GetObjectItemCaseSensitive(root, "password");
    const cJSON* server_url = cJSON_GetObjectItemCaseSensitive(root, "server_url");
    const cJSON* upload_url = cJSON_GetObjectItemCaseSensitive(root, "upload_url");
    const cJSON* command_url = cJSON_GetObjectItemCaseSensitive(root, "command_url");

    if (!cJSON_IsString(login) || !cJSON_IsString(password) || !cJSON_IsString(server_url)) {
        LOG_ERROR("auth config %s is missing login/password/server_url", configPath);
        cJSON_Delete(root);
        return false;
    }

    config_.login = trim(login->valuestring ? login->valuestring : "");
    config_.password = password->valuestring ? password->valuestring : "";
    config_.server_url = normalize_url(server_url->valuestring ? server_url->valuestring : "", "server_url");
    config_.upload_url = cJSON_IsString(upload_url) && upload_url->valuestring
        ? normalize_url(upload_url->valuestring, "upload_url")
        : "";
    config_.command_url = cJSON_IsString(command_url) && command_url->valuestring
        ? normalize_url(command_url->valuestring, "command_url")
        : "";
    cJSON_Delete(root);

    if (config_.login.empty() || config_.password.empty() || config_.server_url.empty()) {
        LOG_ERROR("auth config %s contains empty required fields", configPath);
        return false;
    }

    upload_url_ = config_.upload_url.empty() ? derive_upload_url(config_.server_url) : config_.upload_url;
    command_url_ = config_.command_url.empty() ? derive_command_url(config_.server_url) : config_.command_url;
    ESP_LOGI(TAG_AUTH, "Auth endpoints: login=%s upload=%s command=%s",
             config_.server_url.c_str(), upload_url_.c_str(), command_url_.c_str());
    config_loaded_ = true;
    return true;
}

bool AuthManager::begin(const char* configPath) {
    token_.clear();
    token_expires_at_ms_ = 0;
    offline_mode_ = true;
    config_loaded_ = false;
    upload_url_.clear();
    command_url_.clear();
    return load_config(configPath);
}

bool AuthManager::request_token() {
    if (!config_loaded_) {
        LOG_ERROR("auth manager not initialized");
        offline_mode_ = true;
        return false;
    }

    cJSON* request_root = cJSON_CreateObject();
    if (!request_root) {
        LOG_ERROR("failed to allocate auth JSON request");
        offline_mode_ = true;
        return false;
    }

    const std::string password_hash = sha256_hex(config_.password);
    cJSON_AddStringToObject(request_root, "login", config_.login.c_str());
    // Server expects 'password' field to perform its own hashing/validation
    cJSON_AddStringToObject(request_root, "password", config_.password.c_str());
    char* request_payload = cJSON_PrintUnformatted(request_root);
    cJSON_Delete(request_root);
    if (!request_payload) {
        LOG_ERROR("failed to serialize auth JSON request");
        offline_mode_ = true;
        return false;
    }

    HttpResponseBuffer response;
    esp_http_client_config_t http_cfg = {};
    http_cfg.url = config_.server_url.c_str();
    http_cfg.method = HTTP_METHOD_POST;
    http_cfg.timeout_ms = 10000;
    http_cfg.event_handler = http_event_handler;
    http_cfg.user_data = &response;
    http_cfg.disable_auto_redirect = false;

    ESP_LOGI(TAG_AUTH, "Requesting auth token from %s", config_.server_url.c_str());

    esp_http_client_handle_t client = esp_http_client_init(&http_cfg);
    if (!client) {
        cJSON_free(request_payload);
        LOG_ERROR("failed to create HTTP client for auth");
        offline_mode_ = true;
        return false;
    }

    esp_http_client_set_header(client, "Content-Type", "application/json");
    esp_http_client_set_post_field(client, request_payload, std::strlen(request_payload));

    const esp_err_t perform_err = esp_http_client_perform(client);
    const int status_code = esp_http_client_get_status_code(client);
    esp_http_client_cleanup(client);
    cJSON_free(request_payload);

    if (perform_err != ESP_OK) {
        LOG_ERROR("auth request failed: %s", esp_err_to_name(perform_err));
        offline_mode_ = true;
        return false;
    }
    if (status_code != 200) {
        LOG_ERROR("auth server rejected login with HTTP %d", status_code);
        offline_mode_ = true;
        return false;
    }

    cJSON* response_root = cJSON_Parse(response.body.c_str());
    if (!response_root) {
        LOG_ERROR("failed to parse auth response");
        offline_mode_ = true;
        return false;
    }

    const cJSON* payload = unwrap_api_response_data(response_root);
    const cJSON* token = cJSON_GetObjectItemCaseSensitive(payload, "token");
    const cJSON* expires_in = cJSON_GetObjectItemCaseSensitive(payload, "expires_in");
    if (!cJSON_IsString(token) || !token->valuestring || !cJSON_IsNumber(expires_in)) {
        LOG_ERROR("auth response missing token or expires_in: %s", response.body.c_str());
        cJSON_Delete(response_root);
        offline_mode_ = true;
        return false;
    }

    token_ = token->valuestring;
    token_expires_at_ms_ = now_ms() + static_cast<uint64_t>(expires_in->valuedouble * 1000.0);
    offline_mode_ = false;
    cJSON_Delete(response_root);

    std::string preview = token_.substr(0, token_.size() > 24 ? 24 : token_.size());
    ESP_LOGI(TAG_AUTH, "Token obtained: %s...", preview.c_str());
    return true;
}

bool AuthManager::perform_authorized_get(const std::string& url, std::string& response_body, int& status_code) {
    response_body.clear();
    status_code = 0;

    if (token_.empty() && !refreshToken()) {
        return false;
    }

    HttpResponseBuffer response;
    esp_http_client_config_t http_cfg = {};
    http_cfg.url = url.c_str();
    http_cfg.method = HTTP_METHOD_GET;
    http_cfg.timeout_ms = 10000;
    http_cfg.event_handler = http_event_handler;
    http_cfg.user_data = &response;
    http_cfg.disable_auto_redirect = false;

    esp_http_client_handle_t client = esp_http_client_init(&http_cfg);
    if (!client) {
        LOG_ERROR("failed to create HTTP client for GET %s", url.c_str());
        offline_mode_ = true;
        return false;
    }

    const std::string bearer_header = std::string("Bearer ") + token_;
    esp_http_client_set_header(client, "Authorization", bearer_header.c_str());

    const esp_err_t perform_err = esp_http_client_perform(client);
    status_code = esp_http_client_get_status_code(client);
    esp_http_client_cleanup(client);

    if (perform_err != ESP_OK) {
        LOG_ERROR("authorized GET failed: %s", esp_err_to_name(perform_err));
        offline_mode_ = true;
        return false;
    }

    response_body.swap(response.body);
    return true;
}

std::string AuthManager::getToken() {
    if (is_token_expiring()) {
        refreshToken();
    }
    return token_;
}

bool AuthManager::refreshToken() {
    return request_token();
}

bool AuthManager::fetchRemoteCommand(RemoteCommand& out_command) {
    out_command = {};
    if (!config_loaded_ || command_url_.empty()) {
        return false;
    }

    if (is_token_expiring() && !refreshToken()) {
        return false;
    }

    std::string response_body;
    int status_code = 0;
    if (!perform_authorized_get(command_url_, response_body, status_code)) {
        return false;
    }

    if ((status_code == 401 || status_code == 403) && refreshToken()) {
        if (!perform_authorized_get(command_url_, response_body, status_code)) {
            return false;
        }
    }

    if (status_code != 200) {
        LOG_ERROR("command poll rejected with HTTP %d", status_code);
        offline_mode_ = true;
        return false;
    }

    cJSON* response_root = cJSON_Parse(response_body.c_str());
    if (!response_root) {
        LOG_ERROR("failed to parse command response");
        offline_mode_ = true;
        return false;
    }

    const cJSON* payload = unwrap_api_response_data(response_root);
    const cJSON* recording = cJSON_GetObjectItemCaseSensitive(payload, "recording");
    const cJSON* sequence = cJSON_GetObjectItemCaseSensitive(payload, "sequence");
    if (!cJSON_IsBool(recording) || !cJSON_IsNumber(sequence)) {
        LOG_ERROR("command response missing recording/sequence: %s", response_body.c_str());
        cJSON_Delete(response_root);
        offline_mode_ = true;
        return false;
    }

    out_command.valid = true;
    out_command.should_record = cJSON_IsTrue(recording);
    out_command.sequence = static_cast<uint32_t>(sequence->valuedouble);
    offline_mode_ = false;
    cJSON_Delete(response_root);
    return true;
}
