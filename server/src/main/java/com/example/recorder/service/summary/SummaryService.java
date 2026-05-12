package com.example.recorder.service.summary;

import java.util.concurrent.CompletableFuture;

/**
 * Интерфейс сервиса суммаризации текстов расшифровок.
 * Отправляет текст расшифровки в сервис суммаризации.
 */
public interface SummaryService {

    /**
     * Запуск асинхронной суммаризации для записи.
     *
     * @param recordingId ID записи
     * @param transcriptionText Текст расшифровки аудио
     * @param language Код языка (опционально)
     * @return CompletableFuture с результатом
     */
    CompletableFuture<SummarizationResult> summarizeAsync(String recordingId, String transcriptionText, String language);

    /**
     * Запуск суммаризации для записи (синхронно).
     *
     * @param recordingId ID записи
     * @param transcriptionText Текст расшифровки аудио
     * @param language Код языка (опционально)
     * @return результат суммаризации
     */
    SummarizationResult summarizeSync(String recordingId, String transcriptionText, String language);

    /**
     * Запуск асинхронной суммаризации (без возврата результата).
     * Упрощённый метод для вызова из RecordingService.
     *
     * @param recordingId ID записи
     * @param transcriptionText Текст расшифровки аудио
     * @param language Код языка (опционально)
     */
    void summarize(String recordingId, String transcriptionText, String language);

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
}
