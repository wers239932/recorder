package com.example.recorder.client.transcription;

import com.example.recorder.config.TranscriptionClientProperties;
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
 * Реализация клиента для сервиса расшифровки (ASR) через HTTP/REST.
 * Использует WebClient для асинхронных запросов.
 */
@Slf4j
@Component
public class TranscriptionClientImpl implements TranscriptionClient {

    private final WebClient webClient;
    private final TranscriptionClientProperties properties;

    public TranscriptionClientImpl(@Qualifier("transcriptionWebClient") WebClient webClient, TranscriptionClientProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<TranscriptionResult> transcribe(String recordingId, String audioFilePath, String language) {
        log.debug("Starting transcription for recording {} (file: {})", recordingId, audioFilePath);

        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            return Mono.error(new IllegalArgumentException("Audio file not found: " + audioFilePath));
        }

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new FileSystemResource(audioFile))
            .header("Content-Type", "audio/wav");

        if (language != null && !language.isBlank()) {
            bodyBuilder.part("language", language);
        }

        return webClient.post()
            .uri("/transcribe")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(bodyBuilder.build())
            .retrieve()
            .bodyToMono(TranscriptionResponseDto.class)
            .map(dto -> toTranscriptionResult(dto, recordingId))
            .timeout(Duration.ofMillis(properties.timeout().toMillis()))
            .onErrorResume(WebClientResponseException.class, e -> {
                log.error("ASR service returned error for recording {}: {}", recordingId, e.getMessage());
                return Mono.just(new TranscriptionResult(
                    recordingId,
                    null,
                    null,
                    null,
                    null,
                    TranscriptionStatus.FAILED,
                    "ASR service error: " + e.getMessage()
                ));
            })
            .onErrorResume(e -> {
                log.error("Failed to call ASR service for recording {}: {}", recordingId, e.getMessage());
                return Mono.just(new TranscriptionResult(
                    recordingId,
                    null,
                    null,
                    null,
                    null,
                    TranscriptionStatus.FAILED,
                    "Connection error: " + e.getMessage()
                ));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private TranscriptionResult toTranscriptionResult(TranscriptionResponseDto dto, String recordingId) {
        String text = dto.text();
        
        // Создаём краткую версию (первые 200 символов)
        String briefText = (text != null && text.length() > 200) 
            ? text.substring(0, 200) + "..." 
            : text;

        return new TranscriptionResult(
            recordingId,
            text,
            briefText,
            "ru", // ASR сервис пока не возвращает язык, можно определить автоматически
            0.95, // Confidence score по умолчанию
            TranscriptionStatus.COMPLETED,
            null
        );
    }

    /**
     * DTO ответа от сервиса расшифровки.
     * ASR сервис возвращает: {"text": "..."}
     */
    record TranscriptionResponseDto(
        String text
    ) {}
}
