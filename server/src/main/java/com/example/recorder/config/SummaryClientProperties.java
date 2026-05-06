package com.example.recorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Конфигурация клиента сервиса суммаризации.
 */
@ConfigurationProperties(prefix = "summary.client")
public record SummaryClientProperties(
    /**
     * Базовый URL сервиса суммаризации.
     */
    @DefaultValue("http://localhost:8081")
    String baseUrl,
    
    /**
     * Таймаут запроса.
     */
    @DefaultValue("30s")
    Duration timeout,
    
    /**
     * API ключ для аутентификации.
     */
    @DefaultValue("")
    String apiKey,
    
    /**
     * Включить автоматическую суммаризацию при загрузке.
     */
    @DefaultValue("true")
    Boolean autoSummarize
) {}
