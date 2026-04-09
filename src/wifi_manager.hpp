#pragma once

#include <string>
#include <functional>
#include "esp_err.h"

extern "C" {
#include "esp_event.h"
}

class WiFiManager {
public:
    enum class Mode { OFF, STA, AP };

    struct Status {
        bool is_connected;
        std::string ip_address;
        int32_t rssi;
        Mode mode;
    };

    using StatusCallback = std::function<void(const Status&)>;

    WiFiManager();
    ~WiFiManager();

    // Запрет копирования
    WiFiManager(const WiFiManager&) = delete;
    WiFiManager& operator=(const WiFiManager&) = delete;

    esp_err_t init();
    esp_err_t connect_sta(const std::string& ssid, const std::string& password);
    esp_err_t start_ap(const std::string& ssid, const std::string& password, uint8_t channel = 1);
    esp_err_t stop();

    Status get_status() const;
    void register_status_callback(StatusCallback callback);

private:
    static void event_handler(void* arg, esp_event_base_t event_base, 
                             int32_t event_id, void* event_data);

    Status current_status_;
    StatusCallback status_callback_;
    bool is_initialized_;

    esp_event_handler_instance_t wifi_event_instance_;
    esp_event_handler_instance_t ip_event_instance_;
};