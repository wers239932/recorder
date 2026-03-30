#include "state_processor.hpp"
#include "sd_storage.hpp"
#include "wifi_manager.hpp"
#include "button_handler.hpp"
#include <cstdio>
#include <sstream>
#include <vector>
#include <string>

extern "C" {
#include "esp_timer.h"
}

static const char* TAG = "StateProcessor";

static WiFiManager* g_wifi_manager = nullptr;
static ButtonHandler* g_button = nullptr;
static bool g_creds_file_checked = false;
static std::vector<std::pair<std::string, std::string>> g_wifi_networks;  // SSID, password pairs
static size_t g_current_network_idx = 0;

StateProcessor::StateProcessor(const Config& cfg)
    : config_(cfg), 
      last_state_(Recorder::WAITING_FOR_CREDS),
      last_process_time_ms_(0) {
    printf("%s: initialized with interval %lu ms\n", TAG, config_.process_interval_ms);
    
    // Initialize WiFi manager
    g_wifi_manager = new WiFiManager();
    if (g_wifi_manager->init() == ESP_OK) {
        printf("%s: WiFi manager initialized\n", TAG);
    } else {
        printf("%s: WARNING: WiFi manager init failed\n", TAG);
    }

    // Initialize button handler
    ButtonHandler::Config btn_cfg = ButtonHandler::default_config();
    g_button = new ButtonHandler(btn_cfg);
    if (g_button->init() == ESP_OK) {
        g_button->register_callback([](ButtonHandler::EventType evt){
            if (evt != ButtonHandler::EventType::SHORT_PRESS) return;
            if (!g_wifi_manager) {
                printf("%s: Button ignored: WiFi manager not ready\n", TAG);
                return;
            }
            WiFiManager::Status st = g_wifi_manager->get_status();
            if (!st.is_connected) {
                printf("%s: Button ignored: waiting for WiFi connection\n", TAG);
                return;
            }
            if (Recorder::state == Recorder::READY) {
                if (Recorder::start() == ESP_OK) {
                    Recorder::state = Recorder::RECORDING;
                    printf("%s: Button SHORT_PRESS -> start recording\n", TAG);
                } else {
                    printf("%s: Button SHORT_PRESS -> start failed\n", TAG);
                }
            } else if (Recorder::state == Recorder::RECORDING) {
                Recorder::stop();
                Recorder::state = Recorder::READY;
                printf("%s: Button SHORT_PRESS -> stop recording\n", TAG);
            }
        });
        printf("%s: Button handler initialized\n", TAG);
    } else {
        printf("%s: WARNING: Button init failed\n", TAG);
    }
}

StateProcessor::~StateProcessor() {
    if (g_wifi_manager) {
        g_wifi_manager->stop();
        delete g_wifi_manager;
        g_wifi_manager = nullptr;
    }
    if (g_button) {
        delete g_button;
        g_button = nullptr;
    }
}

uint32_t StateProcessor::get_time_ms() {
    return (uint32_t)(esp_timer_get_time() / 1000);
}

bool StateProcessor::should_process() {
    uint32_t now = get_time_ms();
    if (now - last_process_time_ms_ >= config_.process_interval_ms) {
        last_process_time_ms_ = now;
        return true;
    }
    return false;
}

void StateProcessor::process() {
    // Always tick button handler for debounce and event detection
    if (g_button) {
        g_button->tick();
    }

    if (!should_process()) {
        return;
    }

    Recorder::State current_state = Recorder::state;

    // Log state transitions
    if (current_state != last_state_) {
        printf("%s: state transition %d -> %d\n", TAG, last_state_, current_state);
        last_state_ = current_state;
    }

    // Process based on current state
    switch (current_state) {
        case Recorder::WAITING_FOR_CREDS:
            process_waiting_for_creds();
            break;
        
        case Recorder::READY:
            process_ready();
            break;
        
        case Recorder::RECORDING:
            process_recording();
            break;
        
        case Recorder::SENDING:
            process_sending();
            break;
        
        default:
            printf("%s: unknown state %d\n", TAG, current_state);
            break;
    }
}

void StateProcessor::process_waiting_for_creds() {
    // Handle waiting for credentials state

    // Ensure SD card is mounted before accessing /sdcard
    esp_err_t sd_err = SDStorage::init();
    if (sd_err != ESP_OK) {
        printf("%s: SD not ready (err=%d); will retry\n", TAG, (int)sd_err);
        return;
    }
    if (!g_creds_file_checked) {
        const char* creds_path = "/sdcard/creds";
        printf("%s: WAITING_FOR_CREDS - ensure %s exists and parse if present\n", TAG, creds_path);

        bool created = false;
        if (SDStorage::file_exists(creds_path) != ESP_OK) {
            printf("%s: .creds file not found, creating template\n", TAG);
            std::string template_content =
                "# WiFi credentials format: SSID:Password\n"
                "# One network per line\n";
            esp_err_t werr = SDStorage::write_file(creds_path, template_content);
            if (werr == ESP_OK) {
                created = true;
                g_creds_file_checked = true;
            } else {
                printf("%s: failed to create .creds (err=%d); will retry\n", TAG, (int)werr);
                return;
            }
        }

        g_wifi_networks.clear();
        if (!created) {
            std::string creds_content;
            esp_err_t err = SDStorage::read_file(creds_path, creds_content);
            if (err != ESP_OK) {
                printf("%s: .creds read failed (err=%d); will retry\n", TAG, (int)err);
                return;
            }
            if (!creds_content.empty()) {
                std::istringstream stream(creds_content);
                std::string line;
                while (std::getline(stream, line)) {
                    if (line.empty() || line[0] == '#') continue;
                    while (!line.empty() && (line.back()=='\r' || line.back()=='\n' || line.back()==' ' || line.back()=='\t')) line.pop_back();
                    size_t start = 0; while (start < line.size() && (line[start]==' ' || line[start]=='\t')) start++;
                    std::string trimmed = line.substr(start);
                    size_t colon_pos = trimmed.find(':');
                    if (colon_pos != std::string::npos) {
                        std::string ssid = trimmed.substr(0, colon_pos);
                        std::string password = trimmed.substr(colon_pos + 1);
                        auto rtrim = [](std::string& s){ while (!s.empty() && (s.back()==' ' || s.back()=='\t')) s.pop_back(); };
                        auto ltrim = [](std::string& s){ size_t i=0; while (i<s.size() && (s[i]==' '||s[i]=='\t')) i++; if (i) s.erase(0,i); };
                        rtrim(ssid); ltrim(ssid);
                        rtrim(password); ltrim(password);
                        if (!ssid.empty()) {
                            g_wifi_networks.push_back({ssid, password});
                            printf("%s: parsed network: %s\n", TAG, ssid.c_str());
                        }
                    }
                }
                printf("%s: creds parsed: %zu network(s)\n", TAG, g_wifi_networks.size());
            } else {
                printf("%s: .creds empty\n", TAG);
            }
            g_creds_file_checked = true;
        }

        if (g_wifi_manager && !g_wifi_networks.empty()) {
            printf("%s: Starting WiFi connection attempts (%zu network(s)). AP disabled.\n", TAG, g_wifi_networks.size());
            g_current_network_idx = 0;
            // fallthrough to connection attempt below
        } else {
            printf("%s: No WiFi networks to try; continuing without WiFi (no AP). Switching to READY.\n", TAG);
            Recorder::state = Recorder::READY;
            return;
        }
    }

    // Try to connect to next network in the list (no AP mode)
    if (!g_wifi_networks.empty() && g_current_network_idx < g_wifi_networks.size() && g_wifi_manager) {
        const auto& network = g_wifi_networks[g_current_network_idx];
        printf("%s: Attempting to connect to: %s\n", TAG, network.first.c_str());
        esp_err_t err = g_wifi_manager->connect_sta(network.first, network.second);
        if (err == ESP_OK) {
            printf("%s: Connection attempt started for %s\n", TAG, network.first.c_str());
        } else {
            printf("%s: Failed to start connection for %s (err=%d)\n", TAG, network.first.c_str(), err);
        }
        g_current_network_idx++;
    }

    // Check if WiFi is connected; if not and all tried, continue without WiFi
    if (g_wifi_manager) {
        WiFiManager::Status status = g_wifi_manager->get_status();
        if (status.is_connected) {
            printf("%s: \xE2\x9C\x85 WiFi connected! IP: %s\n", TAG, status.ip_address.c_str());
            Recorder::state = Recorder::READY;
        } else if (g_current_network_idx >= g_wifi_networks.size()) {
            printf("%s: All networks attempted; continuing without WiFi. Switching to READY.\n", TAG);
            Recorder::state = Recorder::READY;
        }
    }
}

void StateProcessor::process_ready() {
    // Handle ready state
    // e.g., check for start recording signal from button handler
    printf("%s: processing READY state\n", TAG);
}

void StateProcessor::process_recording() {
    // Handle recording state
    // e.g., monitor recording progress, check for stop signal
    if (Recorder::is_recording()) {
        printf("%s: processing RECORDING state - recording in progress\n", TAG);
    } else {
        printf("%s: processing RECORDING state - recording stopped unexpectedly\n", TAG);
    }
}

void StateProcessor::process_sending() {
    // Handle sending state
    // e.g., monitor upload progress, handle completion
    printf("%s: processing SENDING state\n", TAG);
}
