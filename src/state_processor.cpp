#include "state_processor.hpp"
#include "sd_storage.hpp"
#include "wifi_manager.hpp"
#include <cstdio>
#include <sstream>
#include <vector>
#include <string>

extern "C" {
#include "esp_timer.h"
}

static const char* TAG = "StateProcessor";

static WiFiManager* g_wifi_manager = nullptr;
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
}

StateProcessor::~StateProcessor() {
    if (g_wifi_manager) {
        g_wifi_manager->stop();
        delete g_wifi_manager;
        g_wifi_manager = nullptr;
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
    if (!g_creds_file_checked) {
        g_creds_file_checked = true;
        printf("%s: WAITING_FOR_CREDS - checking .creds file\n", TAG);
        
        const char* creds_path = "/sdcard/.creds";
        std::string creds_content;
        
        // Try to read existing .creds file
        esp_err_t err = SDStorage::read_file(creds_path, creds_content);
        
        if (err == ESP_OK && !creds_content.empty()) {
            // Parse credentials from file
            printf("%s: .creds file found, parsing networks...\n", TAG);
            std::istringstream stream(creds_content);
            std::string line;
            
            while (std::getline(stream, line)) {
                // Skip empty lines and comments
                if (line.empty() || line[0] == '#') continue;
                
                // Format: SSID:Password
                size_t colon_pos = line.find(':');
                if (colon_pos != std::string::npos) {
                    std::string ssid = line.substr(0, colon_pos);
                    std::string password = line.substr(colon_pos + 1);
                    g_wifi_networks.push_back({ssid, password});
                    printf("%s: Added network: %s\n", TAG, ssid.c_str());
                }
            }
            
            if (!g_wifi_networks.empty()) {
                printf("%s: Starting WiFi connection attempts (%zu networks)\n", TAG, g_wifi_networks.size());
                g_current_network_idx = 0;
            } else {
                printf("%s: .creds file is empty, starting AP mode\n", TAG);
                if (g_wifi_manager) {
                    g_wifi_manager->start_ap("SETUP_ME", "");
                }
            }
        } else {
            // File doesn't exist, create empty one and start AP mode
            printf("%s: .creds file not found, creating empty file\n", TAG);
            std::string empty_content = "# WiFi credentials format: SSID:Password\n";
            SDStorage::write_file(creds_path, empty_content);
            
            printf("%s: Starting AP mode (SETUP_ME)\n", TAG);
            if (g_wifi_manager) {
                g_wifi_manager->start_ap("SETUP_ME", "");
            }
        }
    }
    
    // Try to connect to next network in the list
    if (!g_wifi_networks.empty() && g_current_network_idx < g_wifi_networks.size()) {
        const auto& network = g_wifi_networks[g_current_network_idx];
        printf("%s: Attempting to connect to: %s\n", TAG, network.first.c_str());
        
        if (g_wifi_manager) {
            esp_err_t err = g_wifi_manager->connect_sta(network.first, network.second);
            if (err == ESP_OK) {
                printf("%s: Connection attempt started for %s\n", TAG, network.first.c_str());
            }
        }
        
        g_current_network_idx++;
    }
    
    // Check if WiFi is connected
    if (g_wifi_manager) {
        WiFiManager::Status status = g_wifi_manager->get_status();
        if (status.is_connected) {
            printf("%s: ✅ WiFi connected! IP: %s\n", TAG, status.ip_address.c_str());
            // Transition to READY state
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
