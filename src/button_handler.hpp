#pragma once

#include <functional>
#include <cstdint>

extern "C" {
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
}

class ButtonHandler {
public:
    enum class EventType {
        SHORT_PRESS,  // < 1000 мс
        LONG_PRESS,   // >= 1000 мс
        NONE
    };

    using Callback = std::function<void(EventType)>;

    struct Config {
        gpio_num_t pin;
        bool active_low;
        uint32_t debounce_ms;
        uint32_t long_press_ms;
    };

    // Фабричный метод для получения конфигурации по умолчанию
    static Config default_config() {
        Config cfg;
        cfg.pin = GPIO_NUM_9;
        cfg.active_low = true;
        cfg.debounce_ms = 50;
        cfg.long_press_ms = 1000;
        return cfg;
    }

    explicit ButtonHandler(const Config& config);
    ~ButtonHandler();

    esp_err_t init();
    void register_callback(Callback callback);
    void tick();

private:
    enum class State {
        IDLE,
        DEBOUNCING,
        PRESSED,
        RELEASED
    };

    Config config_;
    State state_;
    uint64_t press_start_time_;
    bool last_raw_state_;
    Callback callback_;
};