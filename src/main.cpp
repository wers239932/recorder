#include "sd_storage.hpp"
#include "display_handler.hpp"
#include "i2s_input.hpp"
#include "recorder.hpp"
#include "state_processor.hpp"
#include <cstdio>

extern "C" {
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
}

static constexpr bool kRunMicSelfTestOnBoot = true;

static void run_mic_self_test() {
    constexpr size_t kWords = 256;  // 256 mono samples * 4 bytes = 1024 bytes
    int32_t raw[kWords] = {};

    printf("MIC self-test: start\n");
    for (int pass = 0; pass < 10; ++pass) {
        const int bytes = I2SInput::read(raw, sizeof(raw), 100);
        if (bytes <= 0) {
            printf("MIC self-test: read=%d\n", bytes);
            continue;
        }

        const size_t frames = (size_t)bytes / sizeof(int32_t);
        int16_t peak = 0;
        for (size_t i = 0; i < frames; ++i) {
            const int16_t sample = I2SInput::raw_to_pcm16(raw[i]);
            const int16_t abs_sample = sample < 0 ? static_cast<int16_t>(-sample) : sample;
            if (abs_sample > peak) peak = abs_sample;
        }

        printf("MIC self-test: bytes=%d samples=%u peak=%d firstRaw=%ld firstPcm=%d\n",
               bytes,
               (unsigned)frames,
               peak,
               frames > 0 ? (long)raw[0] : 0L,
               frames > 0 ? I2SInput::raw_to_pcm16(raw[0]) : 0);
        vTaskDelay(100 / portTICK_PERIOD_MS);
    }
    printf("MIC self-test: done\n");
}

extern "C" void app_main(void) {
    sleep(5);
    printf("Boot\n");

    // ⚠️ КРИТИЧЕСКИ ВАЖНО: СНАЧАЛА SD, ПОТОМ ДИСПЛЕЙ!
    // Если дисплей инициализируется первым — он захватит шину SPI2 и SD не сможет работать
    printf("SD\n");
    esp_err_t err = SDStorage::init();
    if (err != ESP_OK) {
        printf("SD fail: %s\n", esp_err_to_name(err));
        // Остановка — без SD карты запись невозможна
        while (true) {
            printf("SD?\n");
            vTaskDelay(2000 / portTICK_PERIOD_MS);
        }
    }

    // Теперь можно инициализировать дисплей
    printf("LCD\n");
    DisplayHandler::Config disp_cfg{};
    disp_cfg.width = 172;
    disp_cfg.height = 344;
    disp_cfg.rotation = 0;
    disp_cfg.brightness = 50;
    DisplayHandler display(disp_cfg);
    display.init();
    display.clear(DisplayHandler::BLACK);
    display.draw_text(35, 20, "SD Card OK", 2, DisplayHandler::GREEN);

    // Тест записи
    // trimmed
    err = SDStorage::self_test_create_file("/sdcard/test.txt");
    if (err == ESP_OK) {
        display.draw_text(10, 50, "SD Test: PASS", 1, DisplayHandler::GREEN);
        printf("SD test OK\n");
    } else {
        display.draw_text(10, 50, "SD Test: FAIL", 1, DisplayHandler::RED);
        printf("SD test FAIL\n");
    }

    // Инициализация рекордера
    printf("REC\n");
    Recorder::Config rec_cfg{};
    rec_cfg.dir = "/sdcard";
    rec_cfg.sample_rate = 16000;
    rec_cfg.i2s_sample_rate = rec_cfg.sample_rate;
    rec_cfg.bits_per_sample = 16;
    rec_cfg.channels = 1;
    err = Recorder::init(rec_cfg);
    if (err != ESP_OK) {
        printf("REC init fail: %s\n", esp_err_to_name(err));
        display.draw_text(10, 80, "Recorder: FAIL", 1, DisplayHandler::YELLOW);
    } else {
        display.draw_text(10, 80, "Recorder: OK", 1, DisplayHandler::GREEN);
        if (kRunMicSelfTestOnBoot) {
            run_mic_self_test();
        }
    }

    printf("READY\n");
    display.draw_text(10, 110, "READY", 2, DisplayHandler::WHITE);

    // Инициализация обработчика состояния
    // trimmed
        StateProcessor::Config sp_cfg{}
    ;
    sp_cfg.process_interval_ms = 100;  // Проверять состояние каждые 100ms
    StateProcessor state_processor(sp_cfg);
    state_processor.set_display(&display);
    // trimmed


    // Основной цикл - проверяем состояние Recorder и обрабатываем его
    // trimmed
    while (true) {
        // Вызываем обработчик состояния
        // Он проверит текущее состояние Recorder и вызовет соответствующий метод
        state_processor.process();
        
        // Небольшая задержка для предотвращения перегрузки CPU
        vTaskDelay(10 / portTICK_PERIOD_MS);
    }
}
