package com.example.recorder.service.transcription;

import com.example.recorder.client.transcription.TranscriptionClient;
import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.TranscriptionEntity.TranscriptionStatus;
import com.example.recorder.repository.RecordingRepository;
import com.example.recorder.repository.TranscriptionRepository;
import com.example.recorder.service.recording.RecordingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация сервиса для работы с текстовыми расшифровками.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionServiceImpl implements TranscriptionService {

    private final TranscriptionRepository transcriptionRepository;
    private final RecordingRepository recordingRepository;
    private final TranscriptionClient transcriptionClient;
    private final RecordingService recordingService;

    @Override
    @Transactional
    public void startTranscription(String recordingId, String language) {
        RecordingEntity recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Запись не найдена: " + recordingId));

        TranscriptionEntity transcription = transcriptionRepository.findByRecordingId(recordingId)
                .orElse(TranscriptionEntity.builder()
                        .recording(recording)
                        .status(TranscriptionStatus.PENDING)
                        .build());

        transcription.setStatus(TranscriptionStatus.PROCESSING);
        transcription.setStartedAt(LocalDateTime.now());
        transcription.setErrorMessage(null);
        transcription.setRetryCount(0);

        transcriptionRepository.save(transcription);

        // Асинхронный вызов ASR-сервиса
        transcribeAsync(transcription, language);

        log.info("Запущена расшифровка для записи: {}", recordingId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TranscriptionEntity> getTranscriptionByRecordingId(String recordingId) {
        return transcriptionRepository.findByRecordingId(recordingId);
    }

    @Override
    @Transactional(readOnly = true)
    public TranscriptionStatus getTranscriptionStatus(String recordingId) {
        return transcriptionRepository.findByRecordingId(recordingId)
                .map(TranscriptionEntity::getStatus)
                .orElse(TranscriptionStatus.PENDING);
    }

    @Override
    @Transactional
    public TranscriptionEntity saveTranscriptionResult(String recordingId, String transcriptionText,
                                                      String briefText, String detectedLanguage,
                                                      Double confidenceScore) {
        TranscriptionEntity transcription = transcriptionRepository.findByRecordingId(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Расшифровка не найдена: " + recordingId));

        transcription.setTranscriptionText(transcriptionText);
        transcription.setBriefText(briefText);
        transcription.setDetectedLanguage(detectedLanguage);
        transcription.setConfidenceScore(confidenceScore);
        transcription.setStatus(TranscriptionStatus.COMPLETED);
        transcription.setCompletedAt(LocalDateTime.now());

        TranscriptionEntity saved = transcriptionRepository.save(transcription);
        log.info("Сохранен результат расшифровки для записи: {}", recordingId);

        return saved;
    }

    @Override
    @Transactional
    public void setTranscriptionError(String recordingId, String errorMessage) {
        transcriptionRepository.findByRecordingId(recordingId)
                .ifPresent(transcription -> {
                    transcription.setStatus(TranscriptionStatus.FAILED);
                    transcription.setErrorMessage(errorMessage);
                    transcription.setCompletedAt(LocalDateTime.now());
                    transcription.setRetryCount((transcription.getRetryCount() != null ? transcription.getRetryCount() : 0) + 1);

                    transcriptionRepository.save(transcription);
                    log.error("Ошибка расшифровки для записи {}: {}", recordingId, errorMessage);
                });
    }

    @Override
    @Transactional
    public void deleteTranscription(String recordingId) {
        transcriptionRepository.findByRecordingId(recordingId)
                .ifPresent(transcription -> {
                    transcriptionRepository.delete(transcription);
                    log.info("Удалена расшифровка для записи: {}", recordingId);
                });
    }

    /**
     * Асинхронный вызов ASR-сервиса для расшифровки.
     */
    @Async("summaryExecutor")
    public CompletableFuture<Void> transcribeAsync(TranscriptionEntity transcription, String language) {
        String recordingId = transcription.getRecording().getId();

        // Получаем путь к файлу через сервис
        Path filePath = recordingService.getRecordingFilePath(recordingId)
            .orElseThrow(() -> new IllegalArgumentException("Файл не найден для записи: " + recordingId));

        log.info("Вызов ASR-сервиса для расшифровки: {} (файл: {})", recordingId, filePath);

        return transcriptionClient.transcribe(recordingId, filePath.toString(), language)
            .toFuture()
            .thenAccept(result -> {
                if (result.status() == TranscriptionClient.TranscriptionStatus.COMPLETED) {
                    log.info("ASR-сервис вернул расшифровку для записи: {}", recordingId);
                    saveTranscriptionResult(
                        recordingId,
                        result.transcriptionText(),
                        result.briefText(),
                        result.detectedLanguage(),
                        result.confidenceScore()
                    );
                } else {
                    log.error("ASR-сервис вернул ошибку для записи {}: {}", recordingId, result.errorMessage());
                    setTranscriptionError(recordingId, result.errorMessage());
                }
            })
            .exceptionally(ex -> {
                log.error("Ошибка при вызове ASR-сервиса для записи: {}", recordingId, ex);
                setTranscriptionError(recordingId, "Ошибка ASR-сервиса: " + ex.getMessage());
                return null;
            });
    }
}