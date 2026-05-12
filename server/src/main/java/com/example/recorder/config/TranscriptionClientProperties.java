package com.example.recorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Конфигурация клиента сервиса расшифровки (ASR).
 */
@ConfigurationProperties(prefix = "transcription.client")
public record TranscriptionClientProperties(
    /**
     * Базовый URL сервиса расшифровки.
     */
    @DefaultValue("http://localhost:8000")
    String baseUrl,

    /**
     * Таймаут запроса.
     */
    @DefaultValue("60s")
    Duration timeout,

    /**
     * API ключ для аутентификации (если требуется).
     */
    @DefaultValue("")
    String apiKey,

    /**
     * Включить автоматическую расшифровку при загрузке.
     */
    @DefaultValue("false")
    Boolean autoTranscribe
) {}
