#pragma once

#include <string>
#include <cstdint>

class AppState {
public:
    AppState();
    
    void increment();
    void decrement();
    int32_t get_counter() const { return counter_; }
    
    void update_wifi_status(bool connected, const std::string& ip);
    bool is_wifi_connected() const { return wifi_connected_; }
    const std::string& get_ip_address() const { return ip_address_; }

private:
    int32_t counter_;
    bool wifi_connected_;
    std::string ip_address_;
};