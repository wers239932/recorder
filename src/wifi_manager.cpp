#include "wifi_manager.hpp"
#include <cstring>
#include <cstdio>

extern "C" {
#include "esp_netif.h"
#include "esp_wifi.h"
#include "nvs_flash.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
}

static const char* TAG = "WiFiManager";

static constexpr EventBits_t WIFI_CONNECTED_BIT = BIT0;
static EventGroupHandle_t wifi_event_group = nullptr;
static WiFiManager* instance = nullptr;

WiFiManager::WiFiManager() 
    : current_status_{false, "", 0, Mode::OFF}, 
      status_callback_(nullptr), 
      is_initialized_(false),
      wifi_event_instance_(nullptr),
      ip_event_instance_(nullptr) {
    instance = this;
}

WiFiManager::~WiFiManager() {
    if (is_initialized_) {
        if (wifi_event_instance_) {
            esp_event_handler_instance_unregister(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event_instance_);
        }
        if (ip_event_instance_) {
            esp_event_handler_instance_unregister(IP_EVENT, IP_EVENT_STA_GOT_IP, ip_event_instance_);
        }
        esp_wifi_stop();
        esp_wifi_deinit();
        esp_netif_deinit();
        if (wifi_event_group) {
            vEventGroupDelete(wifi_event_group);
            wifi_event_group = nullptr;
        }
    }
    instance = nullptr;
}

esp_err_t WiFiManager::init() {
    if (is_initialized_) return ESP_OK;

    ESP_LOGI(TAG, "Initializing WiFi Manager");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "nvs_flash_init failed: %d", ret);
        return ret;
    }

    ret = esp_netif_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_netif_init failed: %d", ret);
        return ret;
    }

    ret = esp_event_loop_create_default();
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_event_loop_create_default failed: %d", ret);
        return ret;
    }

    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ret = esp_wifi_init(&cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_init failed: %d", ret);
        return ret;
    }

    wifi_event_group = xEventGroupCreate();
    if (!wifi_event_group) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }

    ret = esp_event_handler_instance_register(
        WIFI_EVENT, ESP_EVENT_ANY_ID, 
        &WiFiManager::event_handler,
        nullptr, 
        &wifi_event_instance_
    );
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "WIFI_EVENT register failed: %d", ret);
        return ret;
    }
    
    ret = esp_event_handler_instance_register(
        IP_EVENT, IP_EVENT_STA_GOT_IP,
        &WiFiManager::event_handler,
        nullptr,
        &ip_event_instance_
    );
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "IP_EVENT register failed: %d", ret);
        esp_event_handler_instance_unregister(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event_instance_);
        wifi_event_instance_ = nullptr;
        return ret;
    }

    is_initialized_ = true;
    ESP_LOGI(TAG, "WiFi Manager initialized");
    return ESP_OK;
}

esp_err_t WiFiManager::connect_sta(const std::string& ssid, const std::string& password) {
    if (!is_initialized_) {
        ESP_LOGE(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }

    ESP_LOGI(TAG, "Connecting to SSID: %s", ssid.c_str());

    xEventGroupClearBits(wifi_event_group, WIFI_CONNECTED_BIT);

    wifi_config_t wifi_config = {};
    strncpy(reinterpret_cast<char*>(wifi_config.sta.ssid), ssid.c_str(), sizeof(wifi_config.sta.ssid) - 1);
    strncpy(reinterpret_cast<char*>(wifi_config.sta.password), password.c_str(), sizeof(wifi_config.sta.password) - 1);
    
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    wifi_config.sta.listen_interval = 1;

    esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_start();
    if (ret != ESP_OK) return ret;

    current_status_.mode = Mode::STA;
    current_status_.is_connected = false;
    current_status_.ip_address.clear();
    current_status_.rssi = 0;

    if (status_callback_) {
        status_callback_(current_status_);
    }

    constexpr TickType_t timeout = pdMS_TO_TICKS(20000);
    EventBits_t bits = xEventGroupWaitBits(
        wifi_event_group,
        WIFI_CONNECTED_BIT,
        pdTRUE,
        pdFALSE,
        timeout
    );

    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "Connected successfully, IP: %s", current_status_.ip_address.c_str());
        return ESP_OK;
    }

    ESP_LOGW(TAG, "Connection timeout after %lu ms", timeout * portTICK_PERIOD_MS);
    return ESP_ERR_TIMEOUT;
}

esp_err_t WiFiManager::start_ap(const std::string& ssid, const std::string& password, uint8_t channel) {
    if (!is_initialized_) return ESP_ERR_INVALID_STATE;

    wifi_config_t wifi_config = {};
    strncpy(reinterpret_cast<char*>(wifi_config.ap.ssid), ssid.c_str(), sizeof(wifi_config.ap.ssid) - 1);
    strncpy(reinterpret_cast<char*>(wifi_config.ap.password), password.c_str(), sizeof(wifi_config.ap.password) - 1);
    
    wifi_config.ap.ssid_len = static_cast<uint8_t>(ssid.length());
    wifi_config.ap.max_connection = 4;
    wifi_config.ap.authmode = password.empty() ? WIFI_AUTH_OPEN : WIFI_AUTH_WPA2_PSK;
    wifi_config.ap.channel = channel;

    esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_AP);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_set_config(WIFI_IF_AP, &wifi_config);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_start();
    if (ret != ESP_OK) return ret;

    current_status_.mode = Mode::AP;
    current_status_.is_connected = true;
    current_status_.ip_address = "192.168.4.1";
    current_status_.rssi = 0;

    if (status_callback_) {
        status_callback_(current_status_);
    }

    ESP_LOGI(TAG, "AP started: %s", ssid.c_str());
    return ESP_OK;
}

esp_err_t WiFiManager::stop() {
    if (!is_initialized_) return ESP_OK;
    ESP_LOGI(TAG, "Stopping WiFi");
    return esp_wifi_stop();
}

WiFiManager::Status WiFiManager::get_status() const {
    return current_status_;
}

void WiFiManager::register_status_callback(StatusCallback callback) {
    status_callback_ = std::move(callback);
}

void WiFiManager::event_handler(void* arg, esp_event_base_t event_base, 
                               int32_t event_id, void* event_data) {
    if (!instance) return;

    if (event_base == WIFI_EVENT) {
        switch (event_id) {
            case WIFI_EVENT_STA_START:
                esp_wifi_connect();
                break;

            case WIFI_EVENT_STA_CONNECTED:
                ESP_LOGI(TAG, "WiFi STA Connected (L2)");
                break;

            case WIFI_EVENT_STA_DISCONNECTED: {
                auto* event = static_cast<wifi_event_sta_disconnected_t*>(event_data);
                ESP_LOGW(TAG, "WiFi STA Disconnected. Reason: %d", event->reason);
                
                instance->current_status_.is_connected = false;
                instance->current_status_.ip_address.clear();
                if (instance->status_callback_) {
                    instance->status_callback_(instance->current_status_);
                }

                esp_wifi_connect();
                break;
            }

            default:
                break;
        }
    } 
    else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        auto* event = static_cast<ip_event_got_ip_t*>(event_data);
        char ip_str[20];
        snprintf(ip_str, sizeof(ip_str), IPSTR, IP2STR(&event->ip_info.ip));
        
        instance->current_status_.is_connected = true;
        instance->current_status_.ip_address = ip_str;
        
        wifi_ap_record_t ap_info;
        if (esp_wifi_sta_get_ap_info(&ap_info) == ESP_OK) {
            instance->current_status_.rssi = ap_info.rssi;
        }

        if (instance->status_callback_) {
            instance->status_callback_(instance->current_status_);
        }
        
        // 👇 ИСПРАВЛЕНО: приведение к int для -Werror=format
        ESP_LOGI(TAG, "Got IP: %s, RSSI: %d dBm", ip_str, (int)instance->current_status_.rssi);
        xEventGroupSetBits(wifi_event_group, WIFI_CONNECTED_BIT);
    }
}