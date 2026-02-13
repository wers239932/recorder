#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_system.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "nvs_flash.h"
#include "driver/gpio.h"
#include <LovyanGFX.hpp>
#include <cstring>

// Настройки Wi-Fi (ЗАМЕНИТЕ НА СВОИ!)
#define WIFI_SSID "Srew"
#define WIFI_PASSWORD "239932239"

// Пины дисплея для Waveshare ESP32-C6-LCD-1.47
#define PIN_MOSI 6
#define PIN_SCLK 7
#define PIN_CS   14
#define PIN_DC   15
#define PIN_RST  21
#define PIN_BL   22
#define PIN_BTN  9

// Event Group bits
#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT      BIT1

static EventGroupHandle_t wifi_event_group;
static int retry_num = 0;
static char ip_address[20] = "Not connected";

// Правильное наследование для LovyanGFX v1.x
class LGFX : public lgfx::LGFX_Device {
  lgfx::Bus_SPI _bus_instance;
  lgfx::Panel_ST7789 _panel_instance;
  lgfx::Light_PWM _light_instance;

public:
  LGFX(void) {
    { // Настройка SPI шины
      auto cfg = _bus_instance.config();
      cfg.spi_host = SPI2_HOST;      // ESP32-C6: SPI2 или SPI3
      cfg.spi_mode = 0;
      cfg.freq_write = 40000000;     // 40 МГц
      cfg.pin_sclk = PIN_SCLK;
      cfg.pin_mosi = PIN_MOSI;
      cfg.pin_miso = -1;             // Не используется
      cfg.pin_dc = PIN_DC;
      _bus_instance.config(cfg);
      _panel_instance.setBus(&_bus_instance);
    }

    { // Настройка панели ST7789
      auto cfg = _panel_instance.config();
      cfg.pin_cs = PIN_CS;
      cfg.pin_rst = PIN_RST;
      cfg.pin_busy = -1;
      cfg.memory_width = 172;
      cfg.memory_height = 320;
      cfg.panel_width = 172;
      cfg.panel_height = 320;
      cfg.offset_x = 0;
      cfg.offset_y = 0;
      cfg.offset_rotation = 0;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits = 1;
      cfg.readable = false;
      cfg.invert = true;             // Критично для корректных цветов!
      cfg.rgb_order = false;
      cfg.dlen_16bit = false;
      cfg.bus_shared = true;
      _panel_instance.config(cfg);
    }

    { // Настройка подсветки
      auto cfg = _light_instance.config();
      cfg.pin_bl = PIN_BL;
      cfg.invert = false;
      cfg.freq = 44100;
      cfg.pwm_channel = 0;
      _light_instance.config(cfg);
      _panel_instance.setLight(&_light_instance);
    }

    // Установка панели в базовый класс
    setPanel(&_panel_instance);
  }
};

// Создание глобального объекта дисплея
static LGFX tft;

// Обработчик событий Wi-Fi
static void event_handler(void* arg, esp_event_base_t event_base,
                          int32_t event_id, void* event_data)
{
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        retry_num++;
        if (retry_num < 5) {
            esp_wifi_connect();
        } else {
            xEventGroupSetBits(wifi_event_group, WIFI_FAIL_BIT);
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t* event = (ip_event_got_ip_t*) event_data;
        snprintf(ip_address, sizeof(ip_address), IPSTR, IP2STR(&event->ip_info.ip));
        retry_num = 0;
        xEventGroupSetBits(wifi_event_group, WIFI_CONNECTED_BIT);
    }
}

// Инициализация и подключение к Wi-Fi
void wifi_init_sta(void)
{
    wifi_event_group = xEventGroupCreate();

    esp_netif_init();
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    esp_event_handler_instance_t instance_any_id;
    esp_event_handler_instance_t instance_got_ip;
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT,
                                                        ESP_EVENT_ANY_ID,
                                                        &event_handler,
                                                        NULL,
                                                        &instance_any_id));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT,
                                                        IP_EVENT_STA_GOT_IP,
                                                        &event_handler,
                                                        NULL,
                                                        &instance_got_ip));

    wifi_config_t wifi_config = {};
    strncpy((char*)wifi_config.sta.ssid, WIFI_SSID, sizeof(wifi_config.sta.ssid) - 1);
    strncpy((char*)wifi_config.sta.password, WIFI_PASSWORD, sizeof(wifi_config.sta.password) - 1);

    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    wifi_config.sta.pmf_cfg.capable = true;
    wifi_config.sta.pmf_cfg.required = false;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    ESP_ERROR_CHECK(esp_wifi_start());

    // Ждём подключения или ошибки
    EventBits_t bits = xEventGroupWaitBits(wifi_event_group,
            WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
            pdFALSE,
            pdFALSE,
            portMAX_DELAY);

    if (bits & WIFI_CONNECTED_BIT) {
        printf("Wi-Fi connected! IP: %s\n", ip_address);
    } else if (bits & WIFI_FAIL_BIT) {
        printf("Wi-Fi connection failed\n");
        strcpy(ip_address, "Connection failed");
    }
}

// Вывод статуса Wi-Fi на дисплей
void display_wifi_status()
{
    tft.fillRect(0, 150, 172, 80, TFT_BLACK);
    
    tft.setTextSize(1);
    tft.setTextColor(TFT_CYAN);
    tft.setCursor(10, 155);
    tft.print("Wi-Fi Status:");
    
    if (strstr(ip_address, "Not connected") || strstr(ip_address, "failed")) {
        tft.setTextColor(TFT_RED);
        tft.setCursor(10, 175);
        tft.print(ip_address);
    } else {
        tft.setTextColor(TFT_GREEN);
        tft.setCursor(10, 175);
        tft.print("Connected!");
        
        tft.setTextColor(TFT_YELLOW);
        tft.setCursor(10, 195);
        tft.print("IP: ");
        tft.print(ip_address);
    }
}

extern "C" void app_main(void)
{
    printf("ESP32-C6 Counter with Wi-Fi and LovyanGFX v1.x\n");

    // Инициализация NVS (требуется для Wi-Fi)
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    // Инициализация дисплея
    tft.init();
    tft.setRotation(1);
    tft.fillScreen(TFT_BLACK);
    tft.setBrightness(80);

    // Заголовок
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE);
    tft.setCursor(50, 15);
    tft.print("Counter");

    // Показываем статус "Подключение..." на дисплее
    tft.setTextSize(1);
    tft.setTextColor(TFT_YELLOW);
    tft.setCursor(30, 175);
    tft.print("Connecting to Wi-Fi...");

    // Подключаемся к Wi-Fi (блокирующий вызов)
    wifi_init_sta();

    // Обновляем статус после подключения
    display_wifi_status();

    // Настройка кнопки (активный низкий уровень)
    gpio_set_direction((gpio_num_t)PIN_BTN, GPIO_MODE_INPUT);
    gpio_pullup_en((gpio_num_t)PIN_BTN);

    int counter = 0;
    bool last_state = gpio_get_level((gpio_num_t)PIN_BTN);

    printf("Ready! Press button on GPIO%d\n", PIN_BTN);

    // Вывод начального значения счётчика
    tft.fillRect(60, 50, 100, 70, TFT_BLACK);
    tft.setTextSize(4);
    tft.setTextColor(TFT_WHITE);
    tft.setCursor(75, 65);
    tft.printf("%d", counter);

    while (true) {
        bool current_state = gpio_get_level((gpio_num_t)PIN_BTN);
        
        // Обнаружение нажатия (фронт спадающего фронта)
        if (last_state && !current_state) {
            counter++;
            printf("Count: %d\n", counter);

            // Очистка области счетчика
            tft.fillRect(60, 50, 100, 70, TFT_BLACK);
            
            // Вывод нового значения
            tft.setTextSize(4);
            tft.setTextColor(TFT_WHITE);
            tft.setCursor(75, 65);
            tft.printf("%d", counter);

            // Антидребезг
            vTaskDelay(150 / portTICK_PERIOD_MS);
        }
        
        last_state = current_state;
        vTaskDelay(10 / portTICK_PERIOD_MS);
    }
}