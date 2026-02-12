#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include <LovyanGFX.hpp>

// Пины дисплея для Waveshare ESP32-C6-LCD-1.47
#define PIN_MOSI 6
#define PIN_SCLK 7
#define PIN_CS   14
#define PIN_DC   15
#define PIN_RST  21
#define PIN_BL   22
#define PIN_BTN  9

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

extern "C" void app_main(void)
{
  printf("ESP32-C6 Counter with LovyanGFX v1.x\n");

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

  // Настройка кнопки (активный низкий уровень)
  gpio_set_direction((gpio_num_t)PIN_BTN, GPIO_MODE_INPUT);
  gpio_pullup_en((gpio_num_t)PIN_BTN);

  int counter = 0;
  bool last_state = gpio_get_level((gpio_num_t)PIN_BTN);

  printf("Ready! Press button on GPIO%d\n", PIN_BTN);

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