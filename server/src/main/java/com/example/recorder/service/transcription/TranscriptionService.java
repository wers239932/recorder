package com.example.recorder.service.transcription;

import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.TranscriptionEntity.TranscriptionStatus;

import java.util.Optional;

/**
 * Сервис для работы с текстовыми расшифровками.
 */
public interface TranscriptionService {
    
    /**
     * Запустить расшифровку для записи.
     */
    void startTranscription(String recordingId, String language);
    
    /**
     * Получить расшифровку для записи.
     */
    Optional<TranscriptionEntity> getTranscriptionByRecordingId(String recordingId);
    
    /**
     * Получить статус расшифровки.
     */
    TranscriptionStatus getTranscriptionStatus(String recordingId);
    
    /**
     * Сохранить результат расшифровки.
     */
    TranscriptionEntity saveTranscriptionResult(String recordingId, String transcriptionText, 
                                               String briefText, String detectedLanguage, 
                                               Double confidenceScore);
    
    /**
     * Установить ошибку расшифровки.
     */
    void setTranscriptionError(String recordingId, String errorMessage);
    
    /**
     * Удалить расшифровку для записи.
     */
    void deleteTranscription(String recordingId);
}