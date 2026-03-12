#include "sd_storage.hpp"
#include "display_handler.hpp"
#include "recorder.hpp"
#include "state_processor.hpp"
#include <cstdio>

extern "C" {
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
}

extern "C" void app_main(void) {
    printf("\n=== ESP32-C6 Recorder Boot ===\n");

    // ⚠️ КРИТИЧЕСКИ ВАЖНО: СНАЧАЛА SD, ПОТОМ ДИСПЛЕЙ!
    // Если дисплей инициализируется первым — он захватит шину SPI2 и SD не сможет работать
    printf("\n[1] Initializing SD card...\n");
    esp_err_t err = SDStorage::init();
    if (err != ESP_OK) {
        printf("❌ FATAL: SD init failed (%s). Cannot proceed without SD card!\n", 
               esp_err_to_name(err));
        // Остановка — без SD карты запись невозможна
        while (true) {
            printf("Waiting for SD card...\n");
            vTaskDelay(2000 / portTICK_PERIOD_MS);
        }
    }

    // Теперь можно инициализировать дисплей
    printf("\n[2] Initializing display...\n");
    DisplayHandler::Config disp_cfg{};
    disp_cfg.width = 172;
    disp_cfg.height = 320;
    disp_cfg.rotation = 0;
    disp_cfg.brightness = 50;
    DisplayHandler display(disp_cfg);
    display.init();
    display.clear(DisplayHandler::BLACK);
    display.draw_text(10, 10, "SD Card OK", 2, DisplayHandler::GREEN);

    // Тест записи
    printf("\n[3] Running SD self-test...\n");
    err = SDStorage::self_test_create_file("/sdcard/test.txt");
    if (err == ESP_OK) {
        display.draw_text(10, 50, "SD Test: PASS", 1, DisplayHandler::GREEN);
        printf("✅ SD self-test PASSED\n");
    } else {
        display.draw_text(10, 50, "SD Test: FAIL", 1, DisplayHandler::RED);
        printf("❌ SD self-test FAILED\n");
    }

    // Инициализация рекордера
    printf("\n[4] Initializing recorder...\n");
    Recorder::Config rec_cfg{};
    rec_cfg.dir = "/sdcard/rec";
    rec_cfg.sample_rate = 16000;
    rec_cfg.bits_per_sample = 16;
    rec_cfg.channels = 1;
    err = Recorder::init(rec_cfg);
    if (err != ESP_OK) {
        printf("⚠️ Recorder init failed (%s), but SD is OK\n", esp_err_to_name(err));
        display.draw_text(10, 80, "Recorder: FAIL", 1, DisplayHandler::YELLOW);
    } else {
        display.draw_text(10, 80, "Recorder: OK", 1, DisplayHandler::GREEN);
    }

    printf("\n=== SYSTEM READY ===\n");
    display.draw_text(10, 110, "READY", 2, DisplayHandler::WHITE);

    // Инициализация обработчика состояния
    printf("\n[5] Initializing state processor...\n");
    StateProcessor::Config sp_cfg{};
    sp_cfg.process_interval_ms = 100;  // Проверять состояние каждые 100ms
    StateProcessor state_processor(sp_cfg);
    printf("✅ State processor initialized\n");

    // Основной цикл - проверяем состояние Recorder и обрабатываем его
    printf("\n[6] Entering main loop...\n");
    while (true) {
        // Вызываем обработчик состояния
        // Он проверит текущее состояние Recorder и вызовет соответствующий метод
        state_processor.process();
        
        // Небольшая задержка для предотвращения перегрузки CPU
        vTaskDelay(10 / portTICK_PERIOD_MS);
    }
}