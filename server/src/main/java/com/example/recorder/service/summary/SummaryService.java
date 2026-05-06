package com.example.recorder.service.summary;

import com.example.recorder.entity.RecordingEntity;

import java.util.concurrent.CompletableFuture;

/**
 * Интерфейс сервиса суммаризации аудио записей.
 * Определяет контракт для асинхронной обработки аудиофайлов.
 */
public interface SummaryService {
    
    /**
     * Запуск асинхронной суммаризации для записи.
     * 
     * @param recordingId ID записи
     * @param language Код языка (опционально)
     * @return CompletableFuture с результатом
     */
    CompletableFuture<SummarizationResult> summarizeAsync(String recordingId, String language);
    
    /**
     * Запуск суммаризации для записи (синхронно).
     * 
     * @param recordingId ID записи
     * @param language Код языка (опционально)
     * @return результат суммаризации
     */
    SummarizationResult summarizeSync(String recordingId, String language);
    
    /**
     * Повторная попытка суммаризации для неудачных записей.
     * 
     * @param maxRetries Максимальное количество попыток
     * @return количество запущенных повторных обработок
     */
    int retryFailedSummarizations(int maxRetries);
    
    /**
     * Отмена суммаризации для записи.
     * 
     * @param recordingId ID записи
     * @return true если отменено успешно
     */
    boolean cancelSummarization(String recordingId);
    
    /**
     * Проверка статуса суммаризации.
     * 
     * @param recordingId ID записи
     * @return статус суммаризации
     */
    SummarizationStatus getStatus(String recordingId);
    
    /**
     * Результат суммаризации.
     */
    record SummarizationResult(
        boolean success,
        String summaryId,
        String summaryText,
        String briefSummary,
        String[] keywords,
        Double confidenceScore,
        String detectedLanguage,
        String errorMessage
    ) {}
    
    /**
     * Статус суммаризации.
     */
    enum SummarizationStatus {
        NOT_STARTED,
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
