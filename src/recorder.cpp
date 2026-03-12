#include "recorder.hpp"
#include "sd_storage.hpp"
#include "i2s_input.hpp"
#include "board_pins.hpp"

#include <cstdio>
#include <cstring>
#include <sys/stat.h>
#include <sys/unistd.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"

static const char* TAG_REC = "Recorder";

static Recorder::Config s_cfg;
static volatile bool s_recording = false;
static FILE* s_file = nullptr;
static size_t s_data_bytes = 0;

static void write_wav_header(FILE* f, uint32_t sample_rate, uint16_t bits, uint16_t channels, uint32_t data_bytes) {
    // Minimal WAV header (PCM)
    struct __attribute__((packed)) WavHeader {
        char riff[4];
        uint32_t chunk_size;
        char wave[4];
        char fmt[4];
        uint32_t subchunk1_size;
        uint16_t audio_format;
        uint16_t num_channels;
        uint32_t sample_rate;
        uint32_t byte_rate;
        uint16_t block_align;
        uint16_t bits_per_sample;
        char data[4];
        uint32_t subchunk2_size;
    } hdr;

    memcpy(hdr.riff, "RIFF", 4);
    memcpy(hdr.wave, "WAVE", 4);
    memcpy(hdr.fmt,  "fmt ", 4);
    memcpy(hdr.data, "data", 4);

    hdr.subchunk1_size = 16; // PCM
    hdr.audio_format = 1;    // PCM
    hdr.num_channels = channels;
    hdr.sample_rate = sample_rate;
    hdr.bits_per_sample = bits;
    hdr.block_align = (bits / 8) * channels;
    hdr.byte_rate = sample_rate * hdr.block_align;

    hdr.subchunk2_size = data_bytes;
    hdr.chunk_size = 36 + hdr.subchunk2_size;

    fseek(f, 0, SEEK_SET);
    fwrite(&hdr, 1, sizeof(hdr), f);
}

esp_err_t Recorder::init(const Config& cfg) {
    s_cfg = cfg;

    // Ensure dir exists
    struct stat st = {};
    if (stat(s_cfg.dir.c_str(), &st) != 0) {
        mkdir(s_cfg.dir.c_str(), 0777);
    }

    // I2S init with default pins (placeholder)
    I2SInput::Config icfg{};
    icfg.pin_bclk = PIN_I2S_BCLK;
    icfg.pin_ws = PIN_I2S_WS;
    icfg.pin_din = PIN_I2S_DIN;
    icfg.sample_rate = cfg.sample_rate;
    icfg.bits_per_sample = cfg.bits_per_sample;
    auto err = I2SInput::init(icfg);
    if (err != ESP_OK) return err;

    return ESP_OK;
}

void Recorder::deinit() {
    I2SInput::deinit();
}

esp_err_t Recorder::start() {
    if (s_recording) return ESP_ERR_INVALID_STATE;

    // Create filename with epoch seconds
    char filename[128];
    snprintf(filename, sizeof(filename), "%s/%ld.wav", s_cfg.dir.c_str(), (long) (esp_timer_get_time()/1000000));

    s_file = fopen(filename, "wb+");
    if (!s_file) {
        printf("%s: fopen failed for %s\n", TAG_REC, filename);
        return ESP_FAIL;
    }

    // Reserve space for header
    uint8_t zero_hdr[44] = {0};
    fwrite(zero_hdr, 1, sizeof(zero_hdr), s_file);
    s_data_bytes = 0;
    s_recording = true;

    printf("%s: recording to %s\n", TAG_REC, filename);
    return ESP_OK;
}

void Recorder::stop() {
    if (!s_recording) return;

    // finalize header
    write_wav_header(s_file, s_cfg.sample_rate, s_cfg.bits_per_sample, s_cfg.channels, (uint32_t)s_data_bytes);
    fclose(s_file);
    s_file = nullptr;
    s_recording = false;
    printf("%s: stopped, bytes=%u\n", TAG_REC, (unsigned) s_data_bytes);
}

bool Recorder::is_recording() { return s_recording; }

void Recorder::task_run(void* arg) {
    constexpr size_t kI2SFrameBytes = 4; // 32-bit slot for mono
    constexpr size_t kChunk = 1024;      // bytes to read per call from I2S
    uint8_t* i2s_buf = (uint8_t*) heap_caps_malloc(kChunk, MALLOC_CAP_8BIT);
    int16_t pcm16[512]; // 1024 bytes

    while (true) {
        if (!s_recording) {
            vTaskDelay(10/portTICK_PERIOD_MS);
            continue;
        }
        int n = I2SInput::read(i2s_buf, kChunk, 50);
        if (n < 0) {
            vTaskDelay(5/portTICK_PERIOD_MS);
            continue;
        }
        if (n == 0) continue;

        // Convert from 32-bit right-justified (or left) to 16-bit PCM (take MSB 16 bits)
        size_t samples = n / kI2SFrameBytes;
        for (size_t i = 0; i < samples; ++i) {
            int32_t s32 = ((int32_t*)i2s_buf)[i];
            pcm16[i] = (int16_t)(s32 >> 16); // keep MSB
        }

        size_t bytes_to_write = samples * sizeof(int16_t);
        size_t written = fwrite(pcm16, 1, bytes_to_write, s_file);
        s_data_bytes += written;

        // Optional: flush periodically to reduce data loss
        if ((s_data_bytes & 0xFFFF) == 0) fflush(s_file);
    }
}
