package com.example.recorder.client.summary;

import reactor.core.publisher.Mono;

/**
 * Интерфейс клиента для взаимодействия с сервисом суммаризации.
 * Отправляет текст расшифровки (не аудиофайл) в Summarizer сервис.
 */
public interface SummaryClient {

    /**
     * Отправка текста расшифровки на суммаризацию.
     *
     * @param recordingId ID записи в локальной БД
     * @param transcriptionText Текст расшифровки аудио
     * @param language Код языка (опционально, например "ru", "en")
     * @return результат суммаризации
     */
    Mono<SummaryResult> summarize(String recordingId, String transcriptionText, String language);

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
