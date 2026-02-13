#include "wifi_manager.hpp"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "nvs_flash.h"
#include "freertos/event_groups.h"
#include <cstring>
#include <cstdio>

static constexpr EventBits_t WIFI_CONNECTED_BIT = BIT0;
static constexpr EventBits_t WIFI_FAIL_BIT = BIT1;
static EventGroupHandle_t wifi_event_group = nullptr;
static WiFiManager* instance = nullptr;

WiFiManager::WiFiManager() 
    : current_status_{false, "", 0, Mode::OFF}, 
      status_callback_(nullptr), 
      is_initialized_(false) {
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
    }
}

esp_err_t WiFiManager::init() {
    if (is_initialized_) return ESP_OK;

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        printf("nvs_flash_init failed: %d\n", ret);
        return ret;
    }

    ret = esp_netif_init();
    if (ret != ESP_OK) {
        printf("esp_netif_init failed: %d\n", ret);
        return ret;
    }

    ret = esp_event_loop_create_default();
    if (ret != ESP_OK) {
        printf("esp_event_loop_create_default failed: %d\n", ret);
        return ret;
    }

    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ret = esp_wifi_init(&cfg);
    if (ret != ESP_OK) {
        printf("esp_wifi_init failed: %d\n", ret);
        return ret;
    }

    wifi_event_group = xEventGroupCreate();
    
    // РЕГИСТРИРУЕМ СТАТИЧЕСКИЙ МЕТОД НАПРЯМУЮ (без лямбд!)
        ret = esp_event_handler_instance_register(
        WIFI_EVENT, ESP_EVENT_ANY_ID, 
        &WiFiManager::event_handler,  // Прямой указатель на статический метод
        nullptr, 
        &wifi_event_instance_
    );
    if (ret != ESP_OK) {
        printf("WIFI_EVENT register failed: %d\n", ret);
        return ret;
    }
    
    ret = esp_event_handler_instance_register(
        IP_EVENT, IP_EVENT_STA_GOT_IP,
        &WiFiManager::event_handler,
        nullptr,
        &ip_event_instance_
    );
    if (ret != ESP_OK) {
        printf("IP_EVENT register failed: %d\n", ret);
        esp_event_handler_instance_unregister(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event_instance_);
        return ret;
    }

    is_initialized_ = true;
    return ESP_OK;
}

esp_err_t WiFiManager::connect_sta(const std::string& ssid, const std::string& password) {
    if (!is_initialized_) return ESP_ERR_INVALID_STATE;

    wifi_config_t wifi_config = {};
    strncpy(reinterpret_cast<char*>(wifi_config.sta.ssid), ssid.c_str(), sizeof(wifi_config.sta.ssid) - 1);
    strncpy(reinterpret_cast<char*>(wifi_config.sta.password), password.c_str(), sizeof(wifi_config.sta.password) - 1);
    
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    wifi_config.sta.pmf_cfg.capable = true;
    wifi_config.sta.pmf_cfg.required = false;

    esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
    if (ret != ESP_OK) return ret;

    ret = esp_wifi_start();
    if (ret != ESP_OK) return ret;

    current_status_.mode = Mode::STA;
    current_status_.is_connected = false;
    current_status_.ip_address.clear();

    EventBits_t bits = xEventGroupWaitBits(
        wifi_event_group,
        WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
        pdFALSE,
        pdFALSE,
        pdMS_TO_TICKS(10000)
    );

    if (bits & WIFI_FAIL_BIT) {
        return ESP_FAIL;
    }
    
    return ESP_OK;
}

esp_err_t WiFiManager::start_ap(const std::string& ssid, const std::string& password, uint8_t channel) {
    if (!is_initialized_) return ESP_ERR_INVALID_STATE;

    wifi_config_t wifi_config = {};
    strncpy(reinterpret_cast<char*>(wifi_config.ap.ssid), ssid.c_str(), sizeof(wifi_config.ap.ssid) - 1);
    strncpy(reinterpret_cast<char*>(wifi_config.ap.password), password.c_str(), sizeof(wifi_config.ap.password) - 1);
    
    wifi_config.ap.ssid_len = ssid.length();
    wifi_config.ap.max_connection = 4;
    wifi_config.ap.authmode = password.empty() ? WIFI_AUTH_OPEN : WIFI_AUTH_WPA2_PSK;
    wifi_config.ap.channel = channel;
    wifi_config.ap.pmf_cfg.capable = true;
    wifi_config.ap.pmf_cfg.required = false;

    if (!password.empty() && password.length() < 8) {
        return ESP_ERR_INVALID_ARG;
    }

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

    return ESP_OK;
}

esp_err_t WiFiManager::stop() {
    if (!is_initialized_) return ESP_OK;
    return esp_wifi_stop();
}

WiFiManager::Status WiFiManager::get_status() const {
    return current_status_;
}

void WiFiManager::register_status_callback(StatusCallback callback) {
    status_callback_ = std::move(callback);
}

// ПРАВИЛЬНАЯ РЕАЛИЗАЦИЯ СО СТАНДАРТНОЙ СИГНАТУРОЙ ESP-IDF
void WiFiManager::event_handler(void* arg, esp_event_base_t event_base, 
                               int32_t event_id, void* event_data) {
    if (!instance) return;

    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
        
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        instance->current_status_.is_connected = false;
        if (instance->status_callback_) {
            instance->status_callback_(instance->current_status_);
        }
        esp_wifi_connect();
        
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
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
        
        xEventGroupSetBits(wifi_event_group, WIFI_CONNECTED_BIT);
    }
}