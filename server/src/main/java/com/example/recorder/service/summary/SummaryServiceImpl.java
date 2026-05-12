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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис для управления процессом суммаризации текстов расшифровок.
 * Получает текст расшифровки и отправляет его в сервис суммаризации.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryServiceImpl implements SummaryService {

    private final SummaryClient summaryClient;
    private final SummaryRepository summaryRepository;
    private final RecordingRepository recordingRepository;
    private final SummaryClientProperties properties;

    @Override
    @Async("summaryExecutor")
    @Transactional
    public CompletableFuture<SummarizationResult> summarizeAsync(String recordingId, String transcriptionText, String language) {
        log.info("Starting async summarization for recording: {}", recordingId);

        try {
            SummarizationResult result = summarizeSync(recordingId, transcriptionText, language);
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
    @Transactional
    public void summarize(String recordingId, String transcriptionText, String language) {
        log.info("Starting sync summarization for recording: {}", recordingId);
        try {
            summarizeSync(recordingId, transcriptionText, language);
            log.info("Sync summarization completed for recording: {}", recordingId);
        } catch (Exception e) {
            log.error("Sync summarization failed for recording {}: {}", recordingId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public SummarizationResult summarizeSync(String recordingId, String transcriptionText, String language) {
        log.info("Starting sync summarization for recording: {}", recordingId);

        // Получаем запись
        RecordingEntity recording = recordingRepository.findById(recordingId)
            .orElseThrow(() -> new IllegalArgumentException("Recording not found: " + recordingId));

        // Проверяем текст расшифровки
        if (transcriptionText == null || transcriptionText.isBlank()) {
            throw new IllegalArgumentException("Transcription text is empty for recording: " + recordingId);
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

        // Вызываем внешний сервис суммаризации (отправляем текст)
        var clientResult = summaryClient.summarize(recordingId, transcriptionText, language).block();

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

        log.info("Summarization completed for recording {}: summaryText.length={}, confidence={}",
            recordingId, 
            clientResult.summaryText() != null ? clientResult.summaryText().length() : 0,
            clientResult.confidenceScore());

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
}
