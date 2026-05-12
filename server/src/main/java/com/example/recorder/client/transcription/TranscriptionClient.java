package com.example.recorder.client.transcription;

import reactor.core.publisher.Mono;

/**
 * Интерфейс клиента для взаимодействия с сервисом расшифровки аудио (ASR).
 * Определяет контракт для отправки аудиофайлов и получения текстовой расшифровки.
 */
public interface TranscriptionClient {

    /**
     * Отправка аудиофайла на расшифровку.
     *
     * @param recordingId ID записи в локальной БД
     * @param audioFilePath Путь к WAV-файлу
     * @param language Код языка (опционально, например "ru", "en")
     * @return результат расшифровки
     */
    Mono<TranscriptionResult> transcribe(String recordingId, String audioFilePath, String language);

    /**
     * Результат расшифровки.
     */
    record TranscriptionResult(
        String recordingId,
        String transcriptionText,
        String briefText,
        String detectedLanguage,
        Double confidenceScore,
        TranscriptionStatus status,
        String errorMessage
    ) {}

    /**
     * Статус задачи расшифровки.
     */
    enum TranscriptionStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
