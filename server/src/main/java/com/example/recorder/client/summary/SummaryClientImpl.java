package com.example.recorder.client.summary;

import com.example.recorder.config.SummaryClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.Duration;

/**
 * Реализация клиента для сервиса суммаризации через HTTP/REST.
 * Использует WebClient для асинхронных запросов.
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
    public Mono<SummaryResult> summarize(String recordingId, String audioFilePath, String language) {
        log.debug("Starting summarization for recording {} (file: {})", recordingId, audioFilePath);
        
        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            return Mono.error(new IllegalArgumentException("Audio file not found: " + audioFilePath));
        }
        
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("recording_id", recordingId);
        bodyBuilder.part("audio_file", new FileSystemResource(audioFile))
            .header("Content-Type", "audio/wav");
        
        if (language != null && !language.isBlank()) {
            bodyBuilder.part("language", language);
        }
        
        return webClient.post()
            .uri("/api/v1/summarize")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(bodyBuilder.build())
            .header("X-API-Key", properties.apiKey())
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
    
    @Override
    public Mono<Boolean> cancelSummarization(String taskId) {
        log.debug("Cancelling summarization task {}", taskId);
        
        return webClient.post()
            .uri("/api/v1/summarize/{taskId}/cancel", taskId)
            .header("X-API-Key", properties.apiKey())
            .retrieve()
            .bodyToMono(Boolean.class)
            .onErrorReturn(false);
    }
    
    @Override
    public Mono<SummarizationStatus> getStatus(String taskId) {
        log.debug("Checking status for summarization task {}", taskId);
        
        return webClient.get()
            .uri("/api/v1/summarize/{taskId}/status", taskId)
            .header("X-API-Key", properties.apiKey())
            .retrieve()
            .bodyToMono(StatusResponseDto.class)
            .map(dto -> dto.status())
            .onErrorReturn(SummarizationStatus.FAILED);
    }
    
    private SummaryResult toSummaryResult(SummaryResponseDto dto, String recordingId) {
        return new SummaryResult(
            dto.taskId() != null ? dto.taskId() : recordingId,
            dto.summaryText(),
            dto.briefSummary(),
            dto.keywords(),
            dto.confidenceScore(),
            dto.detectedLanguage(),
            dto.status() != null ? dto.status() : SummarizationStatus.COMPLETED,
            dto.errorMessage()
        );
    }
    
    /**
     * DTO ответа от сервиса суммаризации.
     */
    record SummaryResponseDto(
        String taskId,
        String summaryText,
        String briefSummary,
        String[] keywords,
        Double confidenceScore,
        String detectedLanguage,
        SummarizationStatus status,
        String errorMessage
    ) {}
    
    /**
     * DTO статуса задачи.
     */
    record StatusResponseDto(
        String taskId,
        SummarizationStatus status,
        Integer progressPercent
    ) {}
}
