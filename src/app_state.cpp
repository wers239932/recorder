#include "app_state.hpp"
#include <algorithm>

AppState::AppState() 
    : counter_(0), wifi_connected_(false), ip_address_("Not connected") {}

void AppState::increment() {
    counter_++;
}

void AppState::decrement() {
    counter_ = std::max<int32_t>(0, counter_ - 1);
}

void AppState::update_wifi_status(bool connected, const std::string& ip) {
    wifi_connected_ = connected;
    ip_address_ = ip.empty() ? "No IP" : ip;
}