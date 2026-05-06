package com.example.recorder.service.recording;

import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
     * Получение записи по ID.
     * 
     * @param id ID записи
     * @return информация о записи или пустой Optional
     */
    Optional<RecordingResponse> getRecordingById(String id);
    
    /**
     * Получение списка записей с пагинацией.
     * 
     * @param pageable Параметры пагинации
     * @return страница записей
     */
    Page<RecordingResponse> getAllRecordings(Pageable pageable);
    
    /**
     * Удаление записи по ID.
     * 
     * @param id ID записи
     * @return true если запись удалена
     */
    boolean deleteRecording(String id);
    
    /**
     * Скачивание файла записи.
     * 
     * @param id ID записи
     * @return путь к файлу или пустой Optional
     */
    Optional<java.nio.file.Path> getRecordingFilePath(String id);
    
    /**
     * Статистика хранилища.
     * 
     * @return массив [количество записей, общий размер в байтах]
     */
    long[] getStorageStats();
    
    /**
     * Запуск суммаризации для записи.
     * 
     * @param recordingId ID записи
     * @param language Код языка (опционально)
     */
    void startSummarization(String recordingId, String language);
    
    /**
     * Получение статуса суммаризации.
     * 
     * @param recordingId ID записи
     * @return статус суммаризации
     */
    String getSummarizationStatus(String recordingId);
}
