#include "button_handler.hpp"
#include "esp_timer.h"
#include <algorithm>

ButtonHandler::ButtonHandler(const Config& config) 
    : config_(config), state_(State::IDLE), press_start_time_(0), 
      last_raw_state_(true), callback_(nullptr) {}

ButtonHandler::~ButtonHandler() {
    gpio_reset_pin(config_.pin);
}

esp_err_t ButtonHandler::init() {
    if (!GPIO_IS_VALID_GPIO(config_.pin)) {
        return ESP_ERR_INVALID_ARG;
    }

    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_DISABLE;
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pin_bit_mask = 1ULL << config_.pin;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    
    return gpio_config(&io_conf);
}

void ButtonHandler::register_callback(Callback callback) {
    callback_ = std::move(callback);
}

void ButtonHandler::tick() {
    bool raw_state = (gpio_get_level(config_.pin) == 0) == config_.active_low;
    
    switch (state_) {
        case State::IDLE:
            if (raw_state != last_raw_state_) {
                state_ = State::DEBOUNCING;
                press_start_time_ = esp_timer_get_time();
            }
            break;

        case State::DEBOUNCING:
            if (esp_timer_get_time() - press_start_time_ >= config_.debounce_ms * 1000) {
                if (raw_state) {
                    state_ = State::PRESSED;
                    press_start_time_ = esp_timer_get_time();
                } else {
                    state_ = State::IDLE;
                }
            }
            break;

        case State::PRESSED:
            if (!raw_state) {
                uint32_t press_duration = (esp_timer_get_time() - press_start_time_) / 1000;
                
                if (press_duration >= config_.long_press_ms) {
                    if (callback_) callback_(EventType::LONG_PRESS);
                } else if (press_duration >= config_.debounce_ms) {
                    if (callback_) callback_(EventType::SHORT_PRESS);
                }
                
                state_ = State::IDLE;
            }
            break;

        default:
            state_ = State::IDLE;
            break;
    }

    last_raw_state_ = raw_state;
}