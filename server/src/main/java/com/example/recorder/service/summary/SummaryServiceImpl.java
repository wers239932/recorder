package com.example.recorder.service.summary;

import com.example.recorder.client.summary.SummaryClient;
import com.example.recorder.config.SummaryClientProperties;
import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.SummaryEntity;
import com.example.recorder.repository.RecordingRepository;
import com.example.recorder.repository.SummaryRepository;
import com.example.recorder.service.summary.SummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис для управления процессом суммаризации аудио записей.
 * Обрабатывает аудиофайлы асинхронно через внешний сервис суммаризации.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryServiceImpl implements SummaryService {
    
    private final SummaryClient summaryClient;
    private final SummaryRepository summaryRepository;
    private final RecordingRepository recordingRepository;
    private final SummaryClientProperties properties;

    @Value("${recorder.storage.path:./recordings}")
    private String storagePath;
    
    @Override
    @Async("summaryExecutor")
    @Transactional
    public CompletableFuture<SummarizationResult> summarizeAsync(String recordingId, String language) {
        log.info("Starting async summarization for recording: {}", recordingId);
        
        try {
            SummarizationResult result = summarizeSync(recordingId, language);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Async summarization failed for recording {}: {}", recordingId, e.getMessage(), e);
            return CompletableFuture.completedFuture(new SummarizationResult(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                e.getMessage()
            ));
        }
    }
    
    @Override
    public void summarize(String recordingId, String language) {
        log.info("Starting summarization for recording: {}", recordingId);
        summarizeAsync(recordingId, language);
    }

    @Override
    @Transactional
    public SummarizationResult summarizeSync(String recordingId, String language) {
        log.info("Starting sync summarization for recording: {}", recordingId);

        // Получаем запись
        RecordingEntity recording = recordingRepository.findById(recordingId)
            .orElseThrow(() -> new IllegalArgumentException("Recording not found: " + recordingId));

        // Проверяем файл
        Path audioPath = Paths.get(storagePath, recording.getFilename());
        if (!java.nio.file.Files.exists(audioPath)) {
            throw new IllegalStateException("Audio file not found: " + audioPath);
        }

        // Обновляем статус записи
        recording.setStatus(RecordingEntity.RecordingStatus.SUMMARIZING);
                    recordingRepository.save(recording);
                    
        // Создаём или получаем сущность суммаризации
        SummaryEntity summary = summaryRepository.findByRecordingId(recordingId)
            .orElseGet(() -> {
                SummaryEntity newSummary = SummaryEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .recording(recording)
                    .status(SummaryEntity.SummaryStatus.PENDING)
                    .retryCount(0)
                    .build();
                return summaryRepository.save(newSummary);
            });

        // Обновляем статус суммаризации
        summary.setStatus(SummaryEntity.SummaryStatus.PROCESSING);
        summary.setStartedAt(LocalDateTime.now());
        summary.setRetryCount(summary.getRetryCount() + 1);
        summaryRepository.save(summary);

        // Вызываем внешний сервис суммаризации
        var clientResult = summaryClient.summarize(recordingId, audioPath.toString(), language).block();

        if (clientResult == null || clientResult.status() == SummaryClient.SummarizationStatus.FAILED) {
            // Обработка ошибки
            String errorMsg = clientResult != null
                ? clientResult.errorMessage()
                : "Summary service returned null";

            summary.setStatus(SummaryEntity.SummaryStatus.FAILED);
            summary.setErrorMessage(errorMsg);
            summary.setCompletedAt(LocalDateTime.now());
            summaryRepository.save(summary);

            recording.setStatus(RecordingEntity.RecordingStatus.SUMMARY_FAILED);
            recordingRepository.save(recording);
            log.error("Summarization failed for recording {}: {}", recordingId, errorMsg);

            return new SummarizationResult(
                false,
                summary.getId(),
                null,
                null,
                null,
                null,
                null,
                errorMsg
        );
    }
    
        // Сохраняем результат
        summary.setStatus(SummaryEntity.SummaryStatus.COMPLETED);
        summary.setSummaryText(clientResult.summaryText());
        summary.setBriefSummary(clientResult.briefSummary());
        summary.setKeywords(clientResult.keywords() != null
            ? String.join(",", clientResult.keywords())
            : null);
        summary.setConfidenceScore(clientResult.confidenceScore() != null
            ? BigDecimal.valueOf(clientResult.confidenceScore())
            : null);
        summary.setDetectedLanguage(clientResult.detectedLanguage());
        summary.setCompletedAt(LocalDateTime.now());
        summaryRepository.save(summary);

        // Обновляем статус записи
        recording.setStatus(RecordingEntity.RecordingStatus.READY);
        recordingRepository.save(recording);

        log.info("Summarization completed for recording {}: confidence={}",
            recordingId, clientResult.confidenceScore());

        return new SummarizationResult(
            true,
            summary.getId(),
            clientResult.summaryText(),
            clientResult.briefSummary(),
            clientResult.keywords(),
            clientResult.confidenceScore(),
            clientResult.detectedLanguage(),
            null
        );
    }

    @Override
    @Transactional
    public int retryFailedSummarizations(int maxRetries) {
        log.info("Retrying failed summarizations (max retries: {})", maxRetries);

        List<SummaryEntity> failedSummaries = summaryRepository.findFailedForRetry(maxRetries);
        int startedCount = 0;

        for (SummaryEntity summary : failedSummaries) {
            try {
                summarizeAsync(summary.getRecording().getId(), summary.getDetectedLanguage());
                startedCount++;
                log.info("Started retry for summarization: {}", summary.getId());
            } catch (Exception e) {
                log.error("Failed to start retry for summarization {}: {}", summary.getId(), e.getMessage());
            }
        }

        log.info("Started {} summarization retries", startedCount);
        return startedCount;
    }

    @Override
    @Transactional
    public boolean cancelSummarization(String recordingId) {
        log.debug("Cancelling summarization for recording: {}", recordingId);

        Optional<SummaryEntity> summaryOpt = summaryRepository.findByRecordingId(recordingId);
        if (summaryOpt.isEmpty()) {
            return false;
        }
        
        SummaryEntity summary = summaryOpt.get();
        if (summary.getStatus() != SummaryEntity.SummaryStatus.PROCESSING &&
            summary.getStatus() != SummaryEntity.SummaryStatus.PENDING) {
            log.debug("Cannot cancel summarization in status: {}", summary.getStatus());
            return false;
        }

        // Пытаемся отменить на стороне сервиса
        summaryClient.cancelSummarization(summary.getId()).subscribe(
            cancelled -> {
                if (cancelled) {
                    summary.setStatus(SummaryEntity.SummaryStatus.FAILED);
                    summary.setErrorMessage("Cancelled by user");
                    summary.setCompletedAt(LocalDateTime.now());
                    summaryRepository.save(summary);

                    RecordingEntity recording = summary.getRecording();
                    recording.setStatus(RecordingEntity.RecordingStatus.READY);
                    recordingRepository.save(recording);

                    log.info("Summarization cancelled for recording: {}", recordingId);
                }
            },
            error -> log.error("Error cancelling summarization: {}", error.getMessage())
        );

        return true;
    }

    @Override
    public SummarizationStatus getStatus(String recordingId) {
        Optional<SummaryEntity> summaryOpt = summaryRepository.findByRecordingId(recordingId);

        if (summaryOpt.isEmpty()) {
            return SummarizationStatus.NOT_STARTED;
}

        SummaryEntity summary = summaryOpt.get();
        return switch (summary.getStatus()) {
            case PENDING -> SummarizationStatus.PENDING;
            case PROCESSING -> SummarizationStatus.PROCESSING;
            case COMPLETED -> SummarizationStatus.COMPLETED;
            case FAILED -> SummarizationStatus.FAILED;
        };
    }
}
