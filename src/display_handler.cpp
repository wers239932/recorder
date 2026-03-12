#include "display_handler.hpp"
#include <cstdio>
#include <cstdarg>
#include <algorithm>

extern "C" {
#include "driver/gpio.h"
}

// Реализация вложенного класса LGFX
DisplayHandler::LGFX::LGFX() {
    { // Настройка SPI шины — КРИТИЧЕСКИ ВАЖНО: spi_mode = -1 для совместного использования!
        auto cfg = _bus_instance.config();
        cfg.spi_host = SPI2_HOST;      // Использовать уже инициализированную шину SPI2
        cfg.spi_mode = -1;             // ✅ -1 = НЕ инициализировать шину повторно (требуется для совместного использования с SD)
        cfg.freq_write = 40000000;
        cfg.pin_sclk = 7;   // PIN_SCLK
        cfg.pin_mosi = 6;   // PIN_MOSI
        cfg.pin_miso = 5;   // MISO для совместного использования с SD
        cfg.pin_dc = 15;    // PIN_DC
        _bus_instance.config(cfg);
        _panel_instance.setBus(&_bus_instance);
    }
    { // Настройка панели ST7789
        auto cfg = _panel_instance.config();
        cfg.pin_cs = 14;    // PIN_CS
        cfg.pin_rst = 21;   // PIN_RST
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
        cfg.invert = true;
        cfg.rgb_order = false;
        cfg.dlen_16bit = false;
        // bus_shared для панели — в новых версиях может отсутствовать, поэтому не используем
        _panel_instance.config(cfg);
    }
    { // Настройка подсветки
        auto cfg = _light_instance.config();
        cfg.pin_bl = 22;    // PIN_BL
        cfg.invert = false;
        cfg.freq = 44100;
        cfg.pwm_channel = 0;
        _light_instance.config(cfg);
        _panel_instance.setLight(&_light_instance);
    }
    setPanel(&_panel_instance);
}

DisplayHandler::DisplayHandler(const Config& config)
: config_(config) {}

DisplayHandler::~DisplayHandler() {}

bool DisplayHandler::init() {
    tft_.init();
    tft_.setRotation(config_.rotation);
    clear(BLACK);
    set_brightness(config_.brightness);
    return true;
}

void DisplayHandler::clear(Color color) {
    tft_.fillScreen(color);
}

void DisplayHandler::fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, Color color) {
    tft_.fillRect(x, y, w, h, color);
}

void DisplayHandler::draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, Color color) {
    tft_.drawRect(x, y, w, h, color);
}

void DisplayHandler::draw_text(int16_t x, int16_t y, const std::string& text, uint8_t size, Color color) {
    tft_.setCursor(x, y);
    tft_.setTextSize(size);
    tft_.setTextColor(color);
    tft_.print(text.c_str());
}

void DisplayHandler::draw_textf(int16_t x, int16_t y, uint8_t size, Color color, const char* format, ...) {
    char buffer[128];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    draw_text(x, y, buffer, size, color);
}

void DisplayHandler::update_status_area(const std::string& line1, const std::string& line2, Color color) {
    fill_rect(0, 150, config_.width, 70, BLACK);
    tft_.setTextSize(1);
    tft_.setTextColor(color);
    tft_.setCursor(10, 155);
    tft_.print(line1.c_str());
    tft_.setCursor(10, 175);
    tft_.print(line2.c_str());
}

void DisplayHandler::set_brightness(uint8_t level) {
    level = std::min(level, (uint8_t)100);
    tft_.setBrightness(level * 255 / 100);
}