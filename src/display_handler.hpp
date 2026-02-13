#pragma once

#include <cstdint>
#include <string>
#include <LovyanGFX.hpp>

class DisplayHandler {
public:
    using Color = uint16_t;

    static constexpr Color BLACK   = 0x0000;
    static constexpr Color WHITE   = 0xFFFF;
    static constexpr Color RED     = 0xF800;
    static constexpr Color GREEN   = 0x07E0;
    static constexpr Color BLUE    = 0x001F;
    static constexpr Color CYAN    = 0x07FF;
    static constexpr Color MAGENTA = 0xF81F;
    static constexpr Color YELLOW  = 0xFFE0;
    static constexpr Color GRAY    = 0x8410;

    struct Config {
        uint16_t width;
        uint16_t height;
        uint8_t rotation;
        uint8_t brightness;
    };

    static Config default_config() {
        Config cfg;
        cfg.width = 172;
        cfg.height = 320;
        cfg.rotation = 1;
        cfg.brightness = 80;
        return cfg;
    }

    explicit DisplayHandler(const Config& config);
    ~DisplayHandler();

    bool init();
    void clear(Color color = BLACK);
    void fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, Color color);
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, Color color);
    void draw_text(int16_t x, int16_t y, const std::string& text, uint8_t size, Color color);
    void draw_textf(int16_t x, int16_t y, uint8_t size, Color color, const char* format, ...);
    void update_status_area(const std::string& line1, const std::string& line2, Color color);
    void set_brightness(uint8_t level);
    uint16_t width() const { return config_.width; }
    uint16_t height() const { return config_.height; }

private:
    class LGFX : public lgfx::LGFX_Device {
        lgfx::Bus_SPI _bus_instance;
        lgfx::Panel_ST7789 _panel_instance;
        lgfx::Light_PWM _light_instance;
    public:
        LGFX();
    };

    Config config_;
    LGFX tft_;
};