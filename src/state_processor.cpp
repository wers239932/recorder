#include "state_processor.hpp"
#include "sd_storage.hpp"
#include "auth/auth_manager.h"
#include "wifi_manager.hpp"
#include "button_handler.hpp"
#include "http_uploader.hpp"
#include "display_handler.hpp"
#include <cstdio>
#include <sstream>
#include <vector>
#include <string>

extern "C" {
#include "esp_timer.h"
}

static const char* TAG = "StateProcessor";


static const char* kDefaultUploadUrl = "http://192.168.1.12:8080/api/v1/data/upload";
static constexpr uint32_t kRemotePollIntervalMs = 1000;


static WiFiManager* g_wifi_manager = nullptr;
static AuthManager* g_auth_manager = nullptr;
static ButtonHandler* g_button = nullptr;
static bool g_creds_file_checked = false;
static bool g_auth_file_checked = false;

struct WifiNetwork {
    std::string ssid;
    std::string password;
    std::string username;  // пустой для WPA2-PSK, заполнен для WPA2-Enterprise
    bool is_enterprise;
};
static std::vector<WifiNetwork> g_wifi_networks;
static size_t g_current_network_idx = 0;

namespace {
bool wifi_connected() {
    if (!g_wifi_manager) {
        return false;
    }
    return g_wifi_manager->get_status().is_connected;
}

bool start_recording_from_source(const char* source) {
    if (Recorder::state != Recorder::READY) {
        return false;
    }
    if (Recorder::start() != ESP_OK) {
        printf("%s: %s -> start failed\n", TAG, source);
        return false;
    }
    Recorder::state = Recorder::RECORDING;
    printf("%s: %s -> start recording\n", TAG, source);
    return true;
}

bool stop_recording_and_upload_from_source(const char* source) {
    if (Recorder::state != Recorder::RECORDING) {
        return false;
    }

    Recorder::stop();
    Recorder::state = Recorder::SENDING;
    printf("%s: %s -> stop recording, prepare upload\n", TAG, source);

    std::string wav_path;
    if (!Recorder::get_last_wav_path(wav_path)) {
        printf("%s: %s -> no completed WAV to upload\n", TAG, source);
        Recorder::state = Recorder::READY;
        return false;
    }

    if (!wifi_connected()) {
        printf("%s: %s -> WiFi unavailable, WAV kept on SD\n", TAG, source);
        Recorder::state = Recorder::READY;
        return true;
    }

    const std::string upload_url =
        (g_auth_manager && !g_auth_manager->getUploadUrl().empty())
            ? g_auth_manager->getUploadUrl()
            : std::string(kDefaultUploadUrl);
    const esp_err_t up = HttpUploader::start_wav_upload(upload_url.c_str(), wav_path.c_str());
    if (up != ESP_OK) {
        printf("%s: %s -> upload task start failed (err=%d)\n", TAG, source, static_cast<int>(up));
        Recorder::state = Recorder::READY;
        return false;
    }
    return true;
}
}  // namespace

StateProcessor::StateProcessor(const Config& cfg)
    : config_(cfg), 
      last_state_(Recorder::WAITING_FOR_CREDS),
      last_process_time_ms_(0),
      display_(nullptr),
      last_remote_poll_time_ms_(0),
      last_remote_command_sequence_(0) {
    printf("%s: initialized with interval %lu ms\n", TAG, config_.process_interval_ms);
    
    // Initialize WiFi manager
    g_wifi_manager = new WiFiManager();
    if (g_wifi_manager->init() == ESP_OK) {
        printf("%s: WiFi manager initialized\n", TAG);
    } else {
        printf("%s: WARNING: WiFi manager init failed\n", TAG);
    }
    g_auth_manager = new AuthManager();
    HttpUploader::set_auth_manager(g_auth_manager);

    // Initialize button handler
    ButtonHandler::Config btn_cfg = ButtonHandler::default_config();
    g_button = new ButtonHandler(btn_cfg);
    if (g_button->init() == ESP_OK) {
        g_button->register_callback([](ButtonHandler::EventType evt){
            if (evt != ButtonHandler::EventType::SHORT_PRESS) return;
            if (Recorder::state == Recorder::READY) {
                start_recording_from_source("Button SHORT_PRESS");
            } else if (Recorder::state == Recorder::RECORDING) {
                stop_recording_and_upload_from_source("Button SHORT_PRESS");
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
    HttpUploader::set_auth_manager(nullptr);
    if (g_auth_manager) {
        delete g_auth_manager;
        g_auth_manager = nullptr;
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
    if (display_) display_->update_status_area("STATE: WAITING CREDS", "put /sdcard/creds", DisplayHandler::GRAY);

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
                "# WiFi credentials format:\n"
                "# WPA2-PSK: SSID:Password\n"
                "# WPA2-Enterprise: SSID:Username:Password\n"
                "# One network per line\n"
                "TestWiFi:TestPassword\n";
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
                    
                    // Подсчитываем количество двоеточий для определения формата
                    size_t colon_count = 0;
                    for (char c : trimmed) {
                        if (c == ':') colon_count++;
                    }
                    
                    WifiNetwork network;
                    network.is_enterprise = false;
                    
                    if (colon_count == 1) {
                        // Формат SSID:Password (WPA2-PSK)
                        size_t colon_pos = trimmed.find(':');
                        network.ssid = trimmed.substr(0, colon_pos);
                        network.password = trimmed.substr(colon_pos + 1);
                        network.username = "";
                        network.is_enterprise = false;
                        
                        auto rtrim = [](std::string& s){ while (!s.empty() && (s.back()==' ' || s.back()=='\t')) s.pop_back(); };
                        auto ltrim = [](std::string& s){ size_t i=0; while (i<s.size() && (s[i]==' '||s[i]=='\t')) i++; if (i) s.erase(0,i); };
                        rtrim(network.ssid); ltrim(network.ssid);
                        rtrim(network.password); ltrim(network.password);
                        
                        if (!network.ssid.empty()) {
                            g_wifi_networks.push_back(network);
                            printf("%s: parsed WPA2-PSK network: %s\n", TAG, network.ssid.c_str());
                        }
                    } else if (colon_count == 2) {
                        // Формат SSID:Username:Password (WPA2-Enterprise)
                        size_t first_colon = trimmed.find(':');
                        size_t second_colon = trimmed.find(':', first_colon + 1);
                        network.ssid = trimmed.substr(0, first_colon);
                        network.username = trimmed.substr(first_colon + 1, second_colon - first_colon - 1);
                        network.password = trimmed.substr(second_colon + 1);
                        network.is_enterprise = true;
                        
                        auto rtrim = [](std::string& s){ while (!s.empty() && (s.back()==' ' || s.back()=='\t')) s.pop_back(); };
                        auto ltrim = [](std::string& s){ size_t i=0; while (i<s.size() && (s[i]==' '||s[i]=='\t')) i++; if (i) s.erase(0,i); };
                        rtrim(network.ssid); ltrim(network.ssid);
                        rtrim(network.username); ltrim(network.username);
                        rtrim(network.password); ltrim(network.password);
                        
                        if (!network.ssid.empty() && !network.username.empty()) {
                            g_wifi_networks.push_back(network);
                            printf("%s: parsed WPA2-Enterprise network: %s (user: %s)\n", TAG, network.ssid.c_str(), network.username.c_str());
                        }
                    } else {
                        printf("%s: skipping invalid line (unexpected colon count): %s\n", TAG, trimmed.c_str());
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

    if (!g_auth_file_checked && g_auth_manager) {
        const bool auth_ok = g_auth_manager->begin("/sdcard/auth.txt");
        if (!auth_ok) {
            printf("%s: auth config missing or invalid; template created or retry scheduled\n", TAG);
        } else {
            printf("%s: auth config loaded\n", TAG);
        }
        g_auth_file_checked = auth_ok;
    }

    // Try to connect to next network in the list (no AP mode)
    if (!g_wifi_networks.empty() && g_current_network_idx < g_wifi_networks.size() && g_wifi_manager) {
        const auto& network = g_wifi_networks[g_current_network_idx];
        esp_err_t err;
        
        if (network.is_enterprise) {
            printf("%s: Attempting to connect to Enterprise SSID: %s (user: %s)\n", TAG, network.ssid.c_str(), network.username.c_str());
            err = g_wifi_manager->connect_sta_enterprise(network.ssid, network.username, network.password);
        } else {
            printf("%s: Attempting to connect to SSID: %s\n", TAG, network.ssid.c_str());
            err = g_wifi_manager->connect_sta(network.ssid, network.password);
        }
        
        if (err == ESP_OK) {
            printf("%s: Connection attempt started for %s\n", TAG, network.ssid.c_str());
        } else {
            printf("%s: Failed to start connection for %s (err=%d)\n", TAG, network.ssid.c_str(), err);
        }
        g_current_network_idx++;
    }

    // Check if WiFi is connected; if not and all tried, continue without WiFi
    if (g_wifi_manager) {
        WiFiManager::Status status = g_wifi_manager->get_status();
        if (status.is_connected) {
            printf("%s: \xE2\x9C\x85 WiFi connected! IP: %s\n", TAG, status.ip_address.c_str());
            if (g_auth_manager && g_auth_manager->hasConfig()) {
                if (!g_auth_manager->refreshToken()) {
                    printf("%s: auth server unavailable, continuing in offline mode\n", TAG);
                }
            }
            Recorder::state = Recorder::READY;
        } else if (g_current_network_idx >= g_wifi_networks.size()) {
            printf("%s: All networks attempted; continuing without WiFi. Switching to READY.\n", TAG);
            Recorder::state = Recorder::READY;
        }
    }
}

void StateProcessor::process_ready() {
    if (display_) {
        display_->update_status_area("STATE: READY", "", DisplayHandler::WHITE);
    }

    if (!g_auth_manager || !g_auth_manager->hasConfig() || !wifi_connected()) {
        return;
    }

    const uint32_t now = get_time_ms();
    if (now - last_remote_poll_time_ms_ < kRemotePollIntervalMs) {
        return;
    }
    last_remote_poll_time_ms_ = now;

    AuthManager::RemoteCommand command;
    if (!g_auth_manager->fetchRemoteCommand(command) || !command.valid) {
        return;
    }
    if (command.sequence <= last_remote_command_sequence_) {
        return;
    }

    last_remote_command_sequence_ = command.sequence;
    if (command.should_record) {
        start_recording_from_source("Remote command");
    }
}

void StateProcessor::process_recording() {
    if (Recorder::is_recording()) {
        if (display_) display_->update_status_area("STATE: RECORDING", "press to stop", DisplayHandler::GREEN);
    } else {
        if (display_) display_->update_status_area("STATE: RECORDING", "stopped", DisplayHandler::YELLOW);
    }

    if (!Recorder::is_recording() || !g_auth_manager || !g_auth_manager->hasConfig() || !wifi_connected()) {
        return;
    }

    const uint32_t now = get_time_ms();
    if (now - last_remote_poll_time_ms_ < kRemotePollIntervalMs) {
        return;
    }
    last_remote_poll_time_ms_ = now;

    AuthManager::RemoteCommand command;
    if (!g_auth_manager->fetchRemoteCommand(command) || !command.valid) {
        return;
    }
    if (command.sequence <= last_remote_command_sequence_) {
        return;
    }

    last_remote_command_sequence_ = command.sequence;
    if (!command.should_record) {
        stop_recording_and_upload_from_source("Remote command");
    }
}

void StateProcessor::process_sending() {
    auto st = HttpUploader::get_status();
    const char* phase_str = "";
    switch (st.phase) {
        case HttpUploader::Phase::IDLE: phase_str = "IDLE"; break;
        case HttpUploader::Phase::PREPARING: phase_str = "PREPARING"; break;
        case HttpUploader::Phase::CONNECTING: phase_str = "CONNECTING"; break;
        case HttpUploader::Phase::SENDING_HEADERS: phase_str = "HEADERS"; break;
        case HttpUploader::Phase::SENDING_BODY: phase_str = "BODY"; break;
        case HttpUploader::Phase::WAITING_RESPONSE: phase_str = "WAITING"; break;
        case HttpUploader::Phase::SUCCESS: phase_str = "SUCCESS"; break;
        case HttpUploader::Phase::FAILED: phase_str = "FAILED"; break;
    }
    unsigned pct = (st.total_bytes > 0) ? (unsigned)((st.bytes_sent * 100) / st.total_bytes) : 0;
    printf("%s: SENDING phase=%s http=%d %u/%u (%u%%) errno=%d\n", TAG, phase_str, st.http_code,
           (unsigned)st.bytes_sent, (unsigned)st.total_bytes, pct, st.last_errno);

    if (display_) {
        char line1[64];
        char line2[64];
        snprintf(line1, sizeof(line1), "STATE: SENDING (%s)", phase_str);
        if (st.total_bytes > 0)
            snprintf(line2, sizeof(line2), "%u%% %u/%u", pct, (unsigned)st.bytes_sent, (unsigned)st.total_bytes);
        else
            snprintf(line2, sizeof(line2), "...");
        auto color = (st.phase == HttpUploader::Phase::FAILED) ? DisplayHandler::RED :
                     (st.phase == HttpUploader::Phase::SUCCESS) ? DisplayHandler::GREEN : DisplayHandler::YELLOW;
        display_->update_status_area(line1, line2, color);
    }
}

void StateProcessor::set_display(DisplayHandler* display) {
    display_ = display;
}
