#pragma once

#include <cstdint>
#include <string>

class AuthManager {
public:
    struct RemoteCommand {
        bool valid = false;
        bool should_record = false;
        uint32_t sequence = 0;
    };

    bool begin(const char* configPath = "/sdcard/auth.txt");
    std::string getToken();
    bool refreshToken();
    bool fetchRemoteCommand(RemoteCommand& out_command);

    const std::string& getUploadUrl() const { return upload_url_; }
    const std::string& getCommandUrl() const { return command_url_; }
    bool hasConfig() const { return config_loaded_; }
    bool isOfflineMode() const { return offline_mode_; }

private:
    struct Config {
        std::string login;
        std::string password;
        std::string server_url;
        std::string upload_url;
        std::string command_url;
    };

    static uint64_t now_ms();
    static std::string sha256_hex(const std::string& input);
    static std::string trim(const std::string& value);
    static std::string derive_upload_url(const std::string& login_url);
    static std::string derive_command_url(const std::string& login_url);
    bool load_config(const char* configPath);
    bool request_token();
    bool is_token_expiring() const;
    bool perform_authorized_get(const std::string& url, std::string& response_body, int& status_code);

    Config config_;
    bool config_loaded_ = false;
    bool offline_mode_ = true;
    uint64_t token_expires_at_ms_ = 0;
    std::string token_;
    std::string upload_url_;
    std::string command_url_;
};
