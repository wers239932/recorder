#include "i2s_input.hpp"
#include "board_pins.hpp"
#include <cstdio>

extern "C" {
#include "driver/i2s_std.h"
}

static const char* TAG_I2S = "I2SInput";
static i2s_chan_handle_t s_rx_handle = nullptr;
static bool s_inited = false;

esp_err_t I2SInput::init(const Config& cfg) {
    if (s_inited) return ESP_OK;

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;

    esp_err_t err = i2s_new_channel(&chan_cfg, nullptr, &s_rx_handle);
    if (err != ESP_OK) {
        printf("%s: i2s_new_channel failed: %s\n", TAG_I2S, esp_err_to_name(err));
        return err;
    }

    i2s_std_config_t std_cfg = {};

    // Clock config
    std_cfg.clk_cfg = (i2s_std_clk_config_t){};
    std_cfg.clk_cfg.sample_rate_hz = (uint32_t)cfg.sample_rate;
    std_cfg.clk_cfg.clk_src = I2S_CLK_SRC_DEFAULT;
    std_cfg.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_256;

    // Slot config
    std_cfg.slot_cfg = (i2s_std_slot_config_t){};
    std_cfg.slot_cfg.data_bit_width = I2S_DATA_BIT_WIDTH_24BIT;
    std_cfg.slot_cfg.slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT; // many I2S mics need 32-bit slots; we'll pack to 16 later
    std_cfg.slot_cfg.slot_mode = I2S_SLOT_MODE_STEREO;
    std_cfg.slot_cfg.slot_mask = (i2s_std_slot_mask_t)(I2S_STD_SLOT_LEFT | I2S_STD_SLOT_RIGHT);
    std_cfg.slot_cfg.ws_width = 32;
    std_cfg.slot_cfg.ws_pol = false;
    std_cfg.slot_cfg.bit_shift = true;
    std_cfg.slot_cfg.left_align = false;
    std_cfg.slot_cfg.big_endian = false;
    std_cfg.slot_cfg.bit_order_lsb = false;

    // GPIO config
    std_cfg.gpio_cfg = (i2s_std_gpio_config_t){};
    std_cfg.gpio_cfg.mclk = I2S_GPIO_UNUSED;
    std_cfg.gpio_cfg.bclk = (gpio_num_t)cfg.pin_bclk;
    std_cfg.gpio_cfg.ws = (gpio_num_t)cfg.pin_ws;
    std_cfg.gpio_cfg.dout = I2S_GPIO_UNUSED;
    std_cfg.gpio_cfg.din = (gpio_num_t)cfg.pin_din;
    std_cfg.gpio_cfg.invert_flags.mclk_inv = false;
    std_cfg.gpio_cfg.invert_flags.bclk_inv = false;
    std_cfg.gpio_cfg.invert_flags.ws_inv = false;

    err = i2s_channel_init_std_mode(s_rx_handle, &std_cfg);
    if (err != ESP_OK) {
        printf("%s: i2s_channel_init_std_mode failed: %s\n", TAG_I2S, esp_err_to_name(err));
        return err;
    }

    err = i2s_channel_enable(s_rx_handle);
    if (err == ESP_OK) s_inited = true;
    return err;
}

void I2SInput::deinit() {
    if (!s_inited) return;
    i2s_channel_disable(s_rx_handle);
    i2s_del_channel(s_rx_handle);
    s_rx_handle = nullptr;
    s_inited = false;
}

int I2SInput::read(void* buffer, size_t bytes, uint32_t timeout_ms) {
    if (!s_inited) return -1;
    size_t bytes_read = 0;
    esp_err_t err = i2s_channel_read(s_rx_handle, buffer, bytes, &bytes_read, timeout_ms);
    if (err == ESP_OK) return (int)bytes_read;
    if (err == ESP_ERR_TIMEOUT) return 0;
    return -1;
}
