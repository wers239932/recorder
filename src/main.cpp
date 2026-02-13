#include <cstdio>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "wifi_manager.hpp"
#include "button_handler.hpp"
#include "display_handler.hpp"
#include "app_state.hpp"

// Глобальные объекты
static DisplayHandler display(DisplayHandler::default_config());
static AppState app_state;
static WiFiManager wifi_manager;
static ButtonHandler button_handler(ButtonHandler::default_config());

// Обработчик событий кнопки
static void on_button_event(ButtonHandler::EventType event) {
    switch (event) {
        case ButtonHandler::EventType::SHORT_PRESS:
            app_state.increment();
            printf("SHORT PRESS: Counter = %ld\n", app_state.get_counter());
            break;
            
        case ButtonHandler::EventType::LONG_PRESS:
            app_state.decrement();
            printf("LONG PRESS: Counter = %ld\n", app_state.get_counter());
            break;
            
        default:
            return;
    }

    // Обновляем отображение счётчика
    display.fill_rect(60, 50, 100, 70, DisplayHandler::BLACK);
    display.draw_textf(75, 65, 4, DisplayHandler::WHITE, "%ld", app_state.get_counter());
}

// Обработчик статуса Wi-Fi
static void on_wifi_status(const WiFiManager::Status& status) {
    if (status.is_connected) {
        printf("Wi-Fi connected! IP: %s RSSI: %d dBm\n", 
               status.ip_address.c_str(), status.rssi);
        
        display.update_status_area(
            "Wi-Fi: Connected",
            status.ip_address,
            DisplayHandler::GREEN
        );
    } else {
        printf("Wi-Fi disconnected\n");
        
        display.update_status_area(
            "Wi-Fi: Disconnected",
            "Retrying...",
            DisplayHandler::RED
        );
    }
    
    app_state.update_wifi_status(status.is_connected, status.ip_address);
}

extern "C" void app_main(void) {
    printf("ESP32-C6 Modular Counter App (C++)\n");

    // 1. Инициализация дисплея
    if (!display.init()) {
        printf("Display initialization failed!\n");
        return;
    }

    display.clear(DisplayHandler::BLACK);
    display.draw_text(50, 15, "Counter", 2, DisplayHandler::WHITE);
    display.update_status_area(
        "Wi-Fi: Connecting...",
        "Please wait",
        DisplayHandler::YELLOW
    );

    // 2. Инициализация кнопки
    if (button_handler.init() != ESP_OK) {
        printf("Button initialization failed!\n");
        return;
    }
    button_handler.register_callback(on_button_event);

    // 3. Инициализация и подключение Wi-Fi
    if (wifi_manager.init() != ESP_OK) {
        printf("Wi-Fi initialization failed!\n");
        return;
    }
    
    wifi_manager.register_status_callback(on_wifi_status);
    
    // ⚠️ ЗАМЕНИТЕ НА СВОИ ДАННЫЕ!
    const char* ssid = "Srew";
    const char* password = "239932239";
    
    printf("Connecting to Wi-Fi: %s\n", ssid);
    esp_err_t err = wifi_manager.connect_sta(ssid, password);
    
    if (err != ESP_OK) {
        printf("Wi-Fi connection failed! Starting AP mode...\n");
        wifi_manager.start_ap("ESP32-C6-Counter", "12345678", 6);
    }

    // 4. Инициализация счётчика на дисплее
    display.fill_rect(60, 50, 100, 70, DisplayHandler::BLACK);
    display.draw_textf(75, 65, 4, DisplayHandler::WHITE, "%ld", app_state.get_counter());

    printf("Ready! Short press: +1, Long press (1s): -1\n");

    // 5. Основной цикл
    while (true) {
        button_handler.tick();
        vTaskDelay(10 / portTICK_PERIOD_MS);
    }
}