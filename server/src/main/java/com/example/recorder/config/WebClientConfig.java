package com.example.recorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Конфигурация WebClient для внешних HTTP-вызовов.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * WebClient для сервиса суммаризации.
     */
    @Bean
    public WebClient summaryWebClient(WebClient.Builder builder, SummaryClientProperties properties) {
        return builder
            .baseUrl(properties.baseUrl())
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(16 * 1024 * 1024) // 16MB
            )
            .build();
    }

    /**
     * WebClient для сервиса расшифровки (ASR).
     */
    @Bean
    public WebClient transcriptionWebClient(WebClient.Builder builder, TranscriptionClientProperties properties) {
        return builder
            .baseUrl(properties.baseUrl())
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(50 * 1024 * 1024) // 50MB для аудиофайлов
            )
            .build();
    }
}
