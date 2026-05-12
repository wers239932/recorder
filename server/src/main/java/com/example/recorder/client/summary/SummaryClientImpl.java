package com.example.recorder.client.summary;

import com.example.recorder.config.SummaryClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * Реализация клиента для сервиса суммаризации через HTTP/REST.
 * Использует WebClient для асинхронных запросов.
 * Отправляет текст расшифровки (не аудиофайл) в Summarizer сервис.
 */
@Slf4j
@Component
public class SummaryClientImpl implements SummaryClient {

    private final WebClient webClient;
    private final SummaryClientProperties properties;

    public SummaryClientImpl(@Qualifier("summaryWebClient") WebClient webClient, SummaryClientProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<SummaryResult> summarize(String recordingId, String transcriptionText, String language) {
        log.debug("Starting summarization for recording {} (text length: {})", recordingId, 
            transcriptionText != null ? transcriptionText.length() : 0);

        if (transcriptionText == null || transcriptionText.isBlank()) {
            return Mono.error(new IllegalArgumentException("Transcription text is empty for recording: " + recordingId));
        }

        // Отправляем JSON с текстом расшифровки
        Map<String, Object> requestBody = Map.of(
            "text", transcriptionText
        );

        return webClient.post()
            .uri("/summarize")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(SummaryResponseDto.class)
            .map(dto -> toSummaryResult(dto, recordingId))
            .timeout(Duration.ofMillis(properties.timeout().toMillis()))
            .onErrorResume(WebClientResponseException.class, e -> {
                log.error("Summary service returned error for recording {}: {}", recordingId, e.getMessage());
                return Mono.just(new SummaryResult(
                    recordingId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    SummarizationStatus.FAILED,
                    "Summary service error: " + e.getMessage()
                ));
            })
            .onErrorResume(e -> {
                log.error("Failed to call summary service for recording {}: {}", recordingId, e.getMessage());
                return Mono.just(new SummaryResult(
                    recordingId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    SummarizationStatus.FAILED,
                    "Connection error: " + e.getMessage()
                ));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private SummaryResult toSummaryResult(SummaryResponseDto dto, String recordingId) {
        log.debug("Received summary from Summarizer service for recording {}: length={}", 
            recordingId, dto.summary() != null ? dto.summary().length() : 0);
        
        return new SummaryResult(
            recordingId,                    // taskId
            dto.summary(),                  // summaryText - текст от Summarizer
            null,                           // briefSummary - не возвращается
            null,                           // keywords - не возвращается
            null,                           // confidenceScore - не возвращается Summarizer API
            null,                           // detectedLanguage - не возвращается
            SummarizationStatus.COMPLETED,  // status
            null                            // errorMessage
        );
    }

    /**
     * DTO ответа от сервиса суммаризации.
     * Summarizer сервис возвращает: {"summary": "..."}
     */
    record SummaryResponseDto(
        String summary
    ) {}
}
