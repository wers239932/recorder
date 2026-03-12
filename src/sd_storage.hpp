#pragma once

#include <string>
#include "esp_err.h"

class SDStorage {
public:
    struct Stats {
        uint64_t total_kb = 0;
        uint64_t used_kb = 0;
        uint64_t free_kb = 0;
    };

    // Mount SD (SPI mode) and make it accessible at mount_point (default: "/sdcard")
    static esp_err_t init(const char* mount_point = "/sdcard");
    static void deinit();

    static esp_err_t get_stats(Stats& out);

    // Simple test: create a file and write some bytes
    static esp_err_t self_test_create_file(const char* path = "/sdcard/test.txt");

    // File operations
    static esp_err_t read_file(const char* path, std::string& out_content);
    static esp_err_t write_file(const char* path, const std::string& content);
    static esp_err_t file_exists(const char* path);
};
