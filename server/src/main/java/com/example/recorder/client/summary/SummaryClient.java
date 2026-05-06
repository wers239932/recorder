package com.example.recorder.client.summary;

import reactor.core.publisher.Mono;

/**
 * Интерфейс клиента для взаимодействия с сервисом суммаризации аудио.
 * Определяет контракт для отправки аудиофайлов и получения суммаризации.
 */
public interface SummaryClient {
    
    /**
     * Отправка аудиофайла на суммаризацию.
     * 
     * @param recordingId ID записи в локальной БД
     * @param audioFilePath Путь к WAV-файлу
     * @param language Код языка (опционально, например "ru", "en")
     * @return результат суммаризации
     */
    Mono<SummaryResult> summarize(String recordingId, String audioFilePath, String language);
    
    /**
     * Отмена суммаризации по ID задачи.
     * 
     * @param taskId ID задачи суммаризации
     * @return true если отменено успешно
     */
    Mono<Boolean> cancelSummarization(String taskId);
    
    /**
     * Проверка статуса суммаризации.
     * 
     * @param taskId ID задачи суммаризации
     * @return статус задачи
     */
    Mono<SummarizationStatus> getStatus(String taskId);
    
    /**
     * Результат суммаризации.
     */
    record SummaryResult(
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
     * Статус задачи суммаризации.
     */
    enum SummarizationStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
