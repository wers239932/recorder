package com.example.recorder.service.transcription;

import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.TranscriptionEntity.TranscriptionStatus;
import com.example.recorder.repository.RecordingRepository;
import com.example.recorder.repository.TranscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Реализация сервиса для работы с текстовыми расшифровками.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionServiceImpl implements TranscriptionService {
    
    private final TranscriptionRepository transcriptionRepository;
    private final RecordingRepository recordingRepository;
    
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
        
        // TODO: Здесь должна быть интеграция с сервисом расшифровки
        // Для примера просто завершаем расшифровку
        simulateTranscription(transcription);
        
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
     * Временный метод для симуляции расшифровки.
     * TODO: Заменить на реальную интеграцию с сервисом расшифровки.
     */
    private void simulateTranscription(TranscriptionEntity transcription) {
        // В реальном приложении здесь будет асинхронный вызов внешнего сервиса
        try {
            // Симулируем задержку обработки
            Thread.sleep(1000);
            
            String mockText = "Это текстовая расшифровка аудиозаписи. " +
                    "В реальном приложении здесь будет полный текст расшифровки.";
            
            // Асинхронно сохраняем результат
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Дополнительная задержка
                    saveTranscriptionResult(
                            transcription.getRecording().getId(),
                            mockText,
                            mockText.substring(0, Math.min(100, mockText.length())) + "...",
                            "ru",
                            0.95
                    );
                } catch (Exception e) {
                    log.error("Ошибка при сохранении расшифровки", e);
                    setTranscriptionError(transcription.getRecording().getId(), e.getMessage());
                }
            }).start();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setTranscriptionError(transcription.getRecording().getId(), "Расшифровка прервана");
        }
    }
}