package com.example.recorder.service.recording;

import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import com.example.recorder.entity.RecordingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Интерфейс сервиса для управления аудио записями.
 * Определяет контракт для загрузки, получения и удаления записей.
 */
public interface RecordingService {
    
    /**
     * Загрузка аудио записи.
     *
     * @param file WAV-файл
     * @param request Данные запроса
     * @param clientIp IP адрес клиента
     * @return информация о загруженной записи
     * @throws IOException если произошла ошибка при сохранении
     * @throws IllegalArgumentException если файл некорректен
     */
    RecordingResponse uploadRecording(MultipartFile file, UploadRecordingRequest request, String clientIp) throws IOException;

    /**
     * Загрузка аудио записи для конкретного пользователя.
     */
    RecordingResponse uploadRecordingForUser(MultipartFile file, UploadRecordingRequest request, String clientIp, String userId) throws IOException;

    /**
     * Загрузка аудио записи с указанием пользователя.
     */
    RecordingResponse uploadRecording(MultipartFile file, UploadRecordingRequest request, String clientIp, String userId) throws IOException;

    /**
     * Загрузка аудио записи с указанием пользователя или устройства.
     */
    RecordingResponse uploadRecording(MultipartFile file, UploadRecordingRequest request, String clientIp, String userId, String deviceLogin) throws IOException;

    /**
     * Загрузка записи от имени устройства, привязанного к пользователю.
     */
    RecordingResponse uploadRecordingForUserFromDevice(MultipartFile file, UploadRecordingRequest request, String clientIp, String userId, String deviceLogin) throws IOException;

    /**
     * Получение записи по ID.
     *
     * @param id ID записи
     * @return информация о записи или пустой Optional
     */
    Optional<RecordingResponse> getRecordingById(String id);

    /**
     * Получение записи по ID с проверкой доступа пользователя.
     */
    Optional<RecordingResponse> getRecordingById(String id, String userId);
    /**
     * Получение списка записей с пагинацией.
     *
     * @param pageable Параметры пагинации
     * @return страница записей
     */
    Page<RecordingResponse> getAllRecordings(Pageable pageable);
    /**
     * Получение списка записей пользователя с пагинацией.
     */
    Page<RecordingResponse> getUserRecordings(String userId, Pageable pageable);
    /**
     * Получение всех записей пользователя (без пагинации).
     * Включает записи пользователя и записи с его привязанных устройств.
     */
    List<RecordingResponse> getAllUserRecordings(String userId);
    /**
     * Удаление записи по ID.
     *
     * @param id ID записи
     * @return true если запись удалена
     */
    boolean deleteRecording(String id);

    /**
     * Удаление записи по ID с проверкой доступа пользователя.
     */
    boolean deleteRecording(String id, String userId);

    /**
     * Переименование записи.
     *
     * @param id ID записи
     * @param newFilename Новое имя файла
     * @return true если запись переименована
     */
    boolean renameRecording(String id, String newFilename);

    /**
     * Переименование записи с проверкой доступа пользователя.
     */
    boolean renameRecording(String id, String newFilename, String userId);

    /**
     * Скачивание файла записи.
     *
     * @param id ID записи
     * @return путь к файлу или пустой Optional
     */
    Optional<java.nio.file.Path> getRecordingFilePath(String id);

    /**
     * Скачивание файла записи с проверкой доступа пользователя.
     */
    Optional<java.nio.file.Path> getRecordingFilePath(String id, String userId);

    /**
     * Статистика хранилища.
     *
     * @return массив [количество записей, общий размер в байтах]
     */
    long[] getStorageStats();

    /**
     * Статистика хранилища пользователя.
     */
    long[] getUserStorageStats(String userId);

    /**
     * Запуск суммаризации для записи.
     *
     * @param recordingId ID записи
     * @param language Код языка (опционально)
     */
    void startSummarization(String recordingId, String language);

    /**
     * Запуск суммаризации для записи с проверкой доступа пользователя.
     */
    void startSummarization(String recordingId, String language, String userId);

    /**
     * Запуск транскрипции для уже загруженной сущности записи.
     * Оптимизированный метод, избегает повторного запроса в БД.
     * После завершения транскрипции автоматически запускает суммаризацию (если включено).
     *
     * @param recording Сущность записи
     * @param language Код языка (опционально)
     */
    void startTranscriptionForEntity(RecordingEntity recording, String language);

    /**
     * Запуск суммаризации для уже загруженной сущности записи.
     * Оптимизированный метод, избегает повторного запроса в БД.
     *
     * @param recording Сущность записи
     * @param language Код языка (опционально)
     */
    void startSummarizationForEntity(RecordingEntity recording, String language);

    /**
     * Получение статуса суммаризации.
     *
     * @param recordingId ID записи
     * @return статус суммаризации
     */
    String getSummarizationStatus(String recordingId);

    /**
     * Получение статуса транскрипции.
     *
     * @param recordingId ID записи
     * @return статус транскрипции
     */
    String getTranscriptionStatus(String recordingId);
}
