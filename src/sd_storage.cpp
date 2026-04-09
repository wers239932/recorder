#include "sd_storage.hpp"
#include "board_pins.hpp"
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <sys/stat.h>

extern "C" {
#include "driver/sdspi_host.h"
#include "driver/spi_common.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_vfs_fat.h"
#include "sdmmc_cmd.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
}

static const char* TAG = "SDStorage";
static const char* kDefaultMount = "/sdcard";
static bool s_mounted = false;
static sdmmc_card_t* s_card = nullptr;

esp_err_t SDStorage::init(const char* mount_point) {
    printf("\n%s: === INIT START ===\n", TAG);
    if (s_mounted) {
        printf("%s: already mounted\n", TAG);
        return ESP_OK;
    }

    // 1. Настроить пины CS как выходы и установить в HIGH (idle)
    gpio_config_t io_conf = {};
    io_conf.mode = GPIO_MODE_OUTPUT;
    io_conf.pin_bit_mask = (1ULL << PIN_LCD_CS) | (1ULL << PIN_SD_CS);
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    io_conf.pull_up_en = GPIO_PULLUP_DISABLE;
    io_conf.intr_type = GPIO_INTR_DISABLE;
    printf("DEBUG: PIN_SD_CS value = %d (should be 10)\n", PIN_SD_CS);
    printf("DEBUG: Bitmask = 0x%llX\n", (1ULL << PIN_SD_CS));
    gpio_config(&io_conf);

    // Pull-ups для стабильности линий
    gpio_pullup_en((gpio_num_t)PIN_SD_MISO);
    gpio_pullup_en((gpio_num_t)PIN_LCD_SCLK);
    gpio_pullup_en((gpio_num_t)PIN_LCD_MOSI);
    gpio_pullup_en((gpio_num_t)PIN_SD_CS);
    gpio_pullup_en((gpio_num_t)PIN_LCD_CS);

    printf("%s: Setting CS HIGH: LCD_CS=%d, SD_CS=%d\n", TAG, PIN_LCD_CS, PIN_SD_CS);
    gpio_set_level((gpio_num_t)PIN_LCD_CS, 1);
    gpio_set_level((gpio_num_t)PIN_SD_CS, 1);
    vTaskDelay(10 / portTICK_PERIOD_MS);  // 10 мс для стабилизации

    // 2. Инициализировать шину SPI2 (ТОЛЬКО если ещё не инициализирована!)
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = PIN_LCD_MOSI; // 6
    bus_cfg.miso_io_num = PIN_SD_MISO;  // 5 (обязательно для SD)
    bus_cfg.sclk_io_num = PIN_LCD_SCLK; // 7
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    bus_cfg.max_transfer_sz = 4092;

    esp_err_t err = spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);
    if (err == ESP_ERR_INVALID_STATE) {
        printf("%s: SPI2 already initialized (by display?) - proceeding anyway\n", TAG);
        // Продолжаем, но лучше инициализировать SD ДО дисплея!
    } else if (err != ESP_OK) {
        printf("%s: spi_bus_initialize FAILED: %s\n", TAG, esp_err_to_name(err));
        return err;
    } else {
        printf("%s: SPI2 bus initialized successfully\n", TAG);
    }

    // 3. Убедиться, что оба CS HIGH перед инициализацией SD
    gpio_set_level((gpio_num_t)PIN_LCD_CS, 1);
    gpio_set_level((gpio_num_t)PIN_SD_CS, 1);
    vTaskDelay(10 / portTICK_PERIOD_MS);

    // 4. ⚠️ КРИТИЧЕСКИ ВАЖНО: низкая частота для инициализации SD!
    sdmmc_host_t host = SDSPI_HOST_DEFAULT();
    host.max_freq_khz = 400;  // ✅ 400 кГц вместо 20+ МГц
    host.slot = SPI2_HOST;

    sdspi_device_config_t slot_config = SDSPI_DEVICE_CONFIG_DEFAULT();
    slot_config.host_id = SPI2_HOST;
    slot_config.gpio_cs = (gpio_num_t)PIN_SD_CS; // 10

    esp_vfs_fat_sdmmc_mount_config_t mount_config = {};
    mount_config.format_if_mount_failed = false;
    mount_config.max_files = 4;
    mount_config.allocation_unit_size = 32 * 1024;
    mount_config.disk_status_check_enable = false;

    sdmmc_card_t* card = nullptr;
    printf("%s: Mounting SD card at %s (freq=%d kHz)...\n", 
           TAG, mount_point ? mount_point : kDefaultMount, host.max_freq_khz);
    
    err = esp_vfs_fat_sdspi_mount(mount_point ? mount_point : kDefaultMount,
                                  &host, &slot_config, &mount_config, &card);
    
    if (err != ESP_OK) {
        return err;
    }

    s_mounted = true;
    s_card = card;
    sdmmc_card_print_info(stdout, card);

    // Создать директорию для записей
    struct stat st;
    const char* rec_dir = "/sdcard/rec";
    if (stat(rec_dir, &st) != 0) {
        mkdir(rec_dir, 0777);
        printf("%s: Created directory %s\n", TAG, rec_dir);
    }

    printf("%s: === INIT COMPLETE ===\n\n", TAG);
    return ESP_OK;
}

void SDStorage::deinit() {
    if (!s_mounted) return;
    printf("%s: unmounting SD card...\n", TAG);
    esp_vfs_fat_sdcard_unmount(kDefaultMount, s_card);
    s_mounted = false;
    s_card = nullptr;
    printf("%s: unmount complete\n", TAG);
}

esp_err_t SDStorage::get_stats(Stats& out) {
    if (!s_mounted) return ESP_ERR_INVALID_STATE;
    FATFS* fs = nullptr;
    DWORD fre_clust, fre_sect, tot_sect;
    int res = f_getfree("/sdcard", &fre_clust, &fs);
    if (res != FR_OK || fs == nullptr) {
        return ESP_FAIL;
    }
    tot_sect = (fs->n_fatent - 2) * fs->csize;
    fre_sect = fre_clust * fs->csize;
    out.total_kb = (uint64_t)tot_sect / 2;
    out.free_kb = (uint64_t)fre_sect / 2;
    out.used_kb = out.total_kb - out.free_kb;
    return ESP_OK;
}

esp_err_t SDStorage::self_test_create_file(const char* path) {
    if (!s_mounted) {
        printf("%s: self_test FAILED: SD not mounted!\n", TAG);
        return ESP_ERR_INVALID_STATE;
    }
    FILE* f = fopen(path, "w");
    if (!f) {
        printf("%s: fopen FAILED for %s (errno=%d: %s)\n", TAG, path, errno, strerror(errno));
        return ESP_FAIL;
    }
    const char* msg = "SD self-test OK\n";
    size_t n = fwrite(msg, 1, strlen(msg), f);
    fclose(f);
    if (n == strlen(msg)) {
        printf("%s: self_test PASSED: wrote %s\n", TAG, path);
        return ESP_OK;
    } else {
        printf("%s: self_test FAILED: wrote %zu/%zu bytes\n", TAG, n, strlen(msg));
        return ESP_FAIL;
    }
}

esp_err_t SDStorage::read_file(const char* path, std::string& out_content) {
    if (!s_mounted) {
        printf("%s: read_file FAILED: SD not mounted!\n", TAG);
        return ESP_ERR_INVALID_STATE;
    }

    FILE* f = fopen(path, "r");
    if (!f) {
        printf("%s: read_file: file not found: %s\n", TAG, path);
        return ESP_FAIL;
    }

    out_content.clear();
    char buffer[256];
    size_t n;
    while ((n = fread(buffer, 1, sizeof(buffer), f)) > 0) {
        out_content.append(buffer, n);
    }

    fclose(f);
    printf("%s: read_file SUCCESS: %s (%zu bytes)\n", TAG, path, out_content.size());
    return ESP_OK;
}

esp_err_t SDStorage::write_file(const char* path, const std::string& content) {
    if (!s_mounted) {
        printf("%s: write_file FAILED: SD not mounted!\n", TAG);
        return ESP_ERR_INVALID_STATE;
    }

    FILE* f = fopen(path, "w");
    if (!f) {
        printf("%s: write_file: fopen FAILED for %s (errno=%d: %s)\n", TAG, path, errno, strerror(errno));
        return ESP_FAIL;
    }

    size_t n = fwrite(content.c_str(), 1, content.size(), f);
    fclose(f);

    if (n == content.size()) {
        printf("%s: write_file SUCCESS: %s (%zu bytes)\n", TAG, path, n);
        return ESP_OK;
    } else {
        printf("%s: write_file FAILED: wrote %zu/%zu bytes\n", TAG, n, content.size());
        return ESP_FAIL;
    }
}

esp_err_t SDStorage::file_exists(const char* path) {
    if (!s_mounted) {
        return ESP_ERR_INVALID_STATE;
    }

    struct stat st;
    if (stat(path, &st) == 0) {
        return ESP_OK;
    }
    return ESP_FAIL;
}