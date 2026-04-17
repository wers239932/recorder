#include "recorder.hpp"
#include "sd_storage.hpp"
#include "i2s_input.hpp"
#include "board_pins.hpp"

#include <cstdio>
#include <cerrno>
#include <cstring>
#include <sys/stat.h>
#include <sys/unistd.h>
#include <dirent.h>
#include <strings.h>
#include <ctype.h>
#include <cstdlib>

extern "C" {
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
}

static const char* TAG_REC = "Recorder";

Recorder::State Recorder::state = Recorder::WAITING_FOR_CREDS;  // Initialize state

static Recorder::Config s_cfg;
static volatile bool s_recording = false;
static FILE* s_file = nullptr;
static size_t s_data_bytes = 0;
static TaskHandle_t s_rec_task = nullptr;
static volatile bool s_writer_busy = false;
static std::string s_current_path;
static std::string s_last_completed_path;
static constexpr bool kMicIsLeftChannel = true;
static constexpr size_t kRawSampleBytes = I2SInput::kRawSampleBytes;
static constexpr size_t kReadChunkBytes = 4096;

static int get_next_wav_index() {
    DIR* dir = opendir(s_cfg.dir.c_str());
    if (!dir) return 1;
    int maxn = 0;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        const char* name = ent->d_name;
        size_t len = strlen(name);
        if (len > 4 && strcasecmp(name + len - 4, ".wav") == 0) {
            bool digits_only = true;
            for (size_t i = 0; i < len - 4; ++i) {
                if (!isdigit((unsigned char)name[i])) { digits_only = false; break; }
            }
            if (digits_only) {
                int n = atoi(name);
                if (n > maxn) maxn = n;
            }
        }
    }
    closedir(dir);
    return maxn + 1;
}

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
    icfg.sample_rate = (cfg.i2s_sample_rate != 0) ? cfg.i2s_sample_rate : cfg.sample_rate;
    icfg.bits_per_sample = cfg.bits_per_sample;
    icfg.mic_is_left = kMicIsLeftChannel;
    auto err = I2SInput::init(icfg);
    if (err != ESP_OK) return err;

    printf("%s: wav_rate=%lu i2s_rate=%lu channels=%u bits=%u\n",
           TAG_REC,
           (unsigned long)cfg.sample_rate,
           (unsigned long)icfg.sample_rate,
           (unsigned)cfg.channels,
           (unsigned)cfg.bits_per_sample);

    // Create recorder task if not running yet
    if (s_rec_task == nullptr) {
        BaseType_t ok = xTaskCreate(Recorder::task_run, "rec_task", 4096, nullptr, 5, &s_rec_task);
        if (ok != pdPASS) {
            return ESP_FAIL;
        }
    }
    return ESP_OK;
}

void Recorder::deinit() {
    I2SInput::deinit();
    if (s_rec_task) {
        vTaskDelete(s_rec_task);
        s_rec_task = nullptr;
    }
}

esp_err_t Recorder::start() {
    if (s_recording) return ESP_ERR_INVALID_STATE;

    // Create sequential filename: 1.wav, 2.wav, ... in target dir
    char filename[128];
    int idx = get_next_wav_index();
    snprintf(filename, sizeof(filename), "%s/%d.wav", s_cfg.dir.c_str(), idx);

    s_file = fopen(filename, "wb+");
    if (!s_file) {
        return ESP_FAIL;
    }
    s_current_path = filename;

    // Reserve space for header
    uint8_t zero_hdr[44] = {0};
    fwrite(zero_hdr, 1, sizeof(zero_hdr), s_file);
    s_data_bytes = 0;
    s_recording = true;

    return ESP_OK;
}

void Recorder::stop() {
    if (!s_recording) return;

    // First stop recording to let the writer finish its current chunk
    s_recording = false;
    int waited_ms = 0;
    while (s_writer_busy && waited_ms < 200) { // wait up to 200 ms
        vTaskDelay(5 / portTICK_PERIOD_MS);
        waited_ms += 5;
    }

    if (s_file) {
        // finalize header
        fflush(s_file);
        write_wav_header(s_file, s_cfg.sample_rate, s_cfg.bits_per_sample, s_cfg.channels, (uint32_t)s_data_bytes);
        fclose(s_file);
        s_file = nullptr;
        s_last_completed_path = s_current_path;
        s_current_path.clear();
    }

    // trimmed
}

bool Recorder::is_recording() { return s_recording; }

bool Recorder::get_last_wav_path(std::string& out_path) {
    if (s_last_completed_path.empty()) return false;
    out_path = s_last_completed_path;
    return true;
}

void Recorder::task_run(void* arg) {
    uint8_t* i2s_buf = static_cast<uint8_t*>(heap_caps_malloc(kReadChunkBytes, MALLOC_CAP_DMA | MALLOC_CAP_8BIT));
    int16_t pcm16[kReadChunkBytes / kRawSampleBytes];
    uint32_t debug_counter = 0;
    int64_t started_us = 0;
    size_t samples_captured = 0;

    if (!i2s_buf) {
        printf("%s: failed to allocate I2S DMA buffer\n", TAG_REC);
        vTaskDelete(nullptr);
        return;
    }

    while (true) {
        if (!s_recording) {
            s_writer_busy = false;
            started_us = 0;
            samples_captured = 0;
            vTaskDelay(10/portTICK_PERIOD_MS);
            continue;
        }
        if (started_us == 0) {
            started_us = esp_timer_get_time();
        }

        int n = I2SInput::read(i2s_buf, kReadChunkBytes, 50);
        if (n < 0) {
            s_writer_busy = false;
            vTaskDelay(5/portTICK_PERIOD_MS);
            continue;
        }
        if (n == 0) {
            s_writer_busy = false;
            continue;
        }
        if ((n % kRawSampleBytes) != 0) {
            n -= (n % kRawSampleBytes);
        }
        if (n == 0) {
            s_writer_busy = false;
            continue;
        }

        // I2S runs in mono on the selected slot, so each 32-bit word is one microphone sample.
        size_t samples = n / kRawSampleBytes;
        const int32_t* words = reinterpret_cast<const int32_t*>(i2s_buf);
        int16_t peak = 0;
        for (size_t i = 0; i < samples; ++i) {
            const int16_t pcm = I2SInput::raw_to_pcm16(words[i]);
            pcm16[i] = pcm;
            const int16_t abs_pcm = pcm < 0 ? static_cast<int16_t>(-pcm) : pcm;
            if (abs_pcm > peak) peak = abs_pcm;
        }
        samples_captured += samples;

        size_t bytes_to_write = samples * sizeof(int16_t);
        s_writer_busy = true;
        if (s_file) {
            size_t written = fwrite(pcm16, 1, bytes_to_write, s_file);
            s_data_bytes += written;
            if (written != bytes_to_write) {
                printf("%s: short write: want=%u wrote=%u errno=%d\n",
                       TAG_REC, (unsigned)bytes_to_write, (unsigned)written, errno);
            }
        }
        s_writer_busy = false;

        if ((++debug_counter % 32U) == 0U) {
            const int64_t elapsed_us = esp_timer_get_time() - started_us;
            const unsigned measured_rate = (elapsed_us > 0)
                ? (unsigned)((samples_captured * 1000000ULL) / (uint64_t)elapsed_us)
                : 0U;
            printf("%s: read=%d bytes samples=%u peak=%d first=%d rate=%uHz target=%luHz\n",
                   TAG_REC, n, (unsigned)samples, peak, pcm16[0], measured_rate,
                   (unsigned long)((s_cfg.i2s_sample_rate != 0) ? s_cfg.i2s_sample_rate : s_cfg.sample_rate));
        }

        if ((s_data_bytes & 0xFFFF) == 0) fflush(s_file);
    }
}
