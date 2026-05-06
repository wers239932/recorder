package com.example.recorder;

import com.example.recorder.config.SummaryClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Главный класс Spring Boot приложения для сервера аудиомагнитофона.
 * 
 * Сервер принимает WAV-файлы с ESP32-C6 устройства, хранит их в базе данных,
 * автоматически запускает суммаризацию через внешний сервис и предоставляет
 * REST API для управления записями.
 * 
 * Features:
 * - Загрузка WAV-файлов через multipart/form-data
 * - Хранение метаданных в БД (H2/PostgreSQL)
 * - Асинхронная суммаризация через внешний сервис
 * - REST API с пагинацией и валидацией
 * - Flyway миграции для управления схемой БД
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    SummaryClientProperties.class
})
public class RecorderServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecorderServerApplication.class, args);
    }
}
