package com.example.recorder.service.recording;

import com.example.recorder.config.SummaryClientProperties;
import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.SummaryEntity;
import com.example.recorder.repository.DeviceRepository;
import com.example.recorder.repository.RecordingRepository;
import com.example.recorder.repository.SummaryRepository;
import com.example.recorder.service.summary.SummaryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Реализация сервиса для управления аудио записями.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingServiceImpl implements RecordingService {

    private final RecordingRepository recordingRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryService summaryService;
    private final SummaryClientProperties summaryProperties;
    private final DeviceRepository deviceRepository;

    @Value("${recorder.storage.path:./recordings}")
    private String storagePath;

    @Value("${recorder.storage.max-file-size:104857600}")
    private long maxFileSize;

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created storage directory: {}", storagePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize storage directory", e);
        }
    }

    /**
     * Uploads a new audio recording.
     *
     * @param file The uploaded file
     * @param request The upload request containing metadata
     * @param clientIp The client's IP address
     * @return RecordingResponse containing the uploaded recording details
     * @throws IOException If file storage fails
     */
    @Override
    @Transactional
    public RecordingResponse uploadRecording(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp) throws IOException {
        return uploadRecording(file, request, clientIp, null);
    }

    /**
     * Uploads a new audio recording for a specific user.
     *
     * @param file The uploaded file
     * @param request The upload request containing metadata
     * @param clientIp The client's IP address
     * @param userId The ID of the user who owns the recording (can be null for device uploads)
     * @return RecordingResponse containing the uploaded recording details
     * @throws IOException If file storage fails
     */
    @Transactional
    public RecordingResponse uploadRecording(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp,
            String userId) throws IOException {
        return uploadRecording(file, request, clientIp, userId, null);
    }

    /**
     * Uploads a new audio recording with optional user or device association.
     *
     * @param file The uploaded file
     * @param request The upload request containing metadata
     * @param clientIp The client's IP address
     * @param userId The ID of the user who owns the recording (can be null for device uploads)
     * @param deviceLogin The device login from DeviceAuthService (can be null for user uploads)
     * @return RecordingResponse containing the uploaded recording details
     * @throws IOException If file storage fails
     */
    @Transactional
    public RecordingResponse uploadRecording(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp,
            String userId,
            String deviceLogin) throws IOException {

        log.info("Uploading recording: filename={}, size={}, userId={}, deviceLogin={}",
                request.getFilename(), file.getSize(), userId, deviceLogin);

        // Валидация
        validateFile(file);

        // Генерация уникального имени файла
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = UUID.randomUUID().toString();
        String extension = getExtension(request.getFilename());
        String filename = "recording_" + timestamp + "_" + uniqueId.substring(0, 8) + extension;

        // Сохранение файла
        Path filePath = Paths.get(storagePath, filename);
        file.transferTo(filePath.toFile());

        // Создание сущности записи
        RecordingEntity recording = RecordingEntity.builder()
                .id(uniqueId)
                .userId(userId)
                .deviceLogin(deviceLogin)
                .filename(filename)
                .originalFilename(request.getFilename())
                .fileSize(file.getSize())
                .contentType(file.getContentType() != null ? file.getContentType() : "audio/wav")
                .deviceInfo(request.getDeviceInfo())
                .deviceIp(clientIp)
                .status(RecordingEntity.RecordingStatus.UPLOADED)
                .build();
        recordingRepository.save(recording);

        log.info("Recording uploaded successfully: id={}, path={}, userId={}, deviceLogin={}", recording.getId(), filename, userId, deviceLogin);

        // Автоматический запуск суммаризации (если включено в конфигурации)
        if (Boolean.TRUE.equals(summaryProperties.autoSummarize())) {
            startSummarizationForEntity(recording, null);
        }

        return RecordingResponse.fromEntity(recording, "");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordingResponse> getRecordingById(String id) {
        return recordingRepository.findById(id)
                .map(entity -> RecordingResponse.fromEntity(entity, ""));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RecordingResponse> getAllRecordings(Pageable pageable) {
        return recordingRepository.findLatest(pageable)
                .map(entity -> RecordingResponse.fromEntity(entity, ""));
    }

    @Override
    @Transactional
    public boolean deleteRecording(String id) {
        log.info("Deleting recording: {}", id);

        Optional<RecordingEntity> recordingOpt = recordingRepository.findById(id);
        if (recordingOpt.isEmpty()) {
            return false;
        }

        RecordingEntity recording = recordingOpt.get();

        // Удаление файла
        try {
            Path filePath = Paths.get(storagePath, recording.getFilename());
            Files.deleteIfExists(filePath);
            log.info("Deleted file: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", e.getMessage());
        }

        // Удаление сущности (суммаризация удалится каскадом)
        recordingRepository.delete(recording);
        log.info("Recording deleted from database: {}", id);

        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Path> getRecordingFilePath(String id) {
        return recordingRepository.findById(id)
                .map(recording -> Paths.get(storagePath, recording.getFilename()));
    }

    @Override
    public Optional<Path> getRecordingFilePath(String id, String userId) {
        return recordingRepository.findById(id)
                .filter(recording -> userId.equals(recording.getUserId()))
                .map(recording -> Paths.get(storagePath, recording.getFilename()));
    }

    @Override
    @Transactional(readOnly = true)
    public long[] getStorageStats() {
        long count = recordingRepository.count();
        long totalSize = recordingRepository.findAll().stream()
                .mapToLong(RecordingEntity::getFileSize)
                .sum();
        return new long[]{count, totalSize};
    }

    @Override
    @Transactional(readOnly = true)
    public long[] getUserStorageStats(String userId) {
        List<RecordingEntity> recordings = recordingRepository.findByUserId(userId);
        long count = recordings.size();
        long totalSize = recordings.stream()
                .mapToLong(RecordingEntity::getFileSize)
                .sum();
        return new long[]{count, totalSize};
    }

    @Override
    @Transactional
    public void startSummarization(String recordingId, String language) {
        log.info("Starting summarization for recording: {}", recordingId);

        RecordingEntity recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Recording not found: " + recordingId));

        if (summaryRepository.existsByRecordingId(recordingId)) {
            log.warn("Summarization already exists for recording: {}", recordingId);
            return;
        }

        SummaryEntity summary = SummaryEntity.builder()
                .id(UUID.randomUUID().toString())
                .recording(recording)
                .status(SummaryEntity.SummaryStatus.PENDING)
                .retryCount(0)
                .build();
        summaryRepository.save(summary);

        summaryService.summarize(recording.getId(), language);
    }

    @Override
    public void startSummarization(String recordingId, String language, String userId) {
        log.info("Starting summarization for recording: {} by user: {}", recordingId, userId);
        
        RecordingEntity recording = recordingRepository.findById(recordingId)
                .filter(r -> userId.equals(r.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Recording not found or access denied: " + recordingId));

        if (summaryRepository.existsByRecordingId(recordingId)) {
            log.warn("Summarization already exists for recording: {}", recordingId);
            return;
        }

        SummaryEntity summary = SummaryEntity.builder()
                .id(UUID.randomUUID().toString())
                .recording(recording)
                .status(SummaryEntity.SummaryStatus.PENDING)
                .retryCount(0)
                .build();
        summaryRepository.save(summary);

        summaryService.summarize(recording.getId(), language);
    }

    @Override
    public String getSummarizationStatus(String recordingId) {
        var status = summaryService.getStatus(recordingId);
        return status.name();
    }

    /**
     * Аналогичный метод, но принимает уже загруженную сущность записи
     * для избежания дополнительных запросов в БД
     */
    @Override
    @Transactional
    public void startSummarizationForEntity(RecordingEntity recording, String language) {
        log.info("Starting summarization for recording: {}", recording.getId());

        // Проверяем, нет ли уже суммаризации
        if (summaryRepository.existsByRecordingId(recording.getId())) {
            log.warn("Summarization already exists for recording: {}", recording.getId());
            return;
        }

        // Создаём сущность суммаризации
        SummaryEntity summary = SummaryEntity.builder()
                .id(UUID.randomUUID().toString())
                .recording(recording)
                .status(SummaryEntity.SummaryStatus.PENDING)
                .retryCount(0)
                .build();
        summaryRepository.save(summary);

        // Запускаем асинхронную суммаризацию
        summaryService.summarize(recording.getId(), language);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException(
                    "File size exceeds limit: " + file.getSize() + " > " + maxFileSize);
        }

        String filename = file.getOriginalFilename();
        if (filename != null && !filename.toLowerCase().endsWith(".wav")) {
            throw new IllegalArgumentException("Only WAV files are supported");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return ".wav";
        }
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }

    /**
     * Подготавливает финальное имя файла, добавляя расширение при необходимости
     */
    private String prepareFilename(String filename, String originalExtension) {
        if (filename == null) {
            return null;
        }

        // Если имя уже имеет допустимое расширение, возвращаем как есть
        if (filename.toLowerCase().endsWith(".wav") ||
                filename.toLowerCase().endsWith(".mp3") ||
                filename.contains(".")) {
            return filename;
        }

        // Иначе добавляем оригинальное расширение
        return filename + originalExtension;
    }

    @Override
    @Transactional
    public RecordingResponse uploadRecordingForUser(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp,
            String userId) throws IOException {
        // Загрузка с привязкой к пользователю
        return uploadRecording(file, request, clientIp, userId, null);
    }

    /**
     * Загрузка записи от имени устройства, привязанного к пользователю.
     *
     * @param file WAV-файл
     * @param request Данные запроса
     * @param clientIp IP адрес клиента
     * @param userId ID пользователя
     * @param deviceLogin Логин устройства
     * @return информация о загруженной записи
     */
    @Transactional
    public RecordingResponse uploadRecordingForUserFromDevice(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp,
            String userId,
            String deviceLogin) throws IOException {
        // Загрузка с привязкой к пользователю и устройству
        return uploadRecording(file, request, clientIp, userId, deviceLogin);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordingResponse> getRecordingById(String id, String userId) {
        return recordingRepository.findById(id)
                .filter(recording -> userId.equals(recording.getUserId()))
                .map(entity -> RecordingResponse.fromEntity(entity, ""));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RecordingResponse> getUserRecordings(String userId, Pageable pageable) {
        return recordingRepository.findByUserId(userId, pageable)
                .map(entity -> RecordingResponse.fromEntity(entity, ""));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordingResponse> getAllUserRecordings(String userId) {
        // Получаем записи пользователя
        List<RecordingEntity> userRecordings = recordingRepository.findByUserId(userId);
        
        // Получаем записи с привязанных устройств пользователя
        List<String> deviceLogins = deviceRepository.findByUserId(userId)
                .stream()
                .map(d -> d.getLogin())
                .toList();
        
        List<RecordingEntity> deviceRecordings = deviceLogins.isEmpty() 
            ? List.of() 
            : recordingRepository.findByDeviceLoginIn(deviceLogins);
        
        // Объединяем и сортируем по дате создания (новые сверху)
        return Stream.concat(userRecordings.stream(), deviceRecordings.stream())
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(entity -> RecordingResponse.fromEntity(entity, ""))
                .toList();
    }

    @Override
    @Transactional
    public boolean deleteRecording(String id, String userId) {
        return recordingRepository.findById(id)
                .filter(recording -> userId.equals(recording.getUserId()))
                .map(recording -> {
                    // Удаление файла
                    try {
                        Path filePath = Paths.get(storagePath, recording.getFilename());
                        Files.deleteIfExists(filePath);
                        log.info("Deleted file: {}", filePath);
                    } catch (IOException e) {
                        log.warn("Failed to delete file: {}", e.getMessage());
                    }

                    // Удаление сущности
                    recordingRepository.delete(recording);
                    log.info("Recording deleted from database: {}", id);

                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean renameRecording(String id, String newFilename) {
        log.info("Renaming recording: {} to {}", id, newFilename);

        return recordingRepository.findById(id)
                .map(recording -> {
                    String oldFilename = recording.getFilename();
                    String extension = getExtension(oldFilename);

                    // ✅ Подготавливаем финальное имя до использования в лямбде
                    String finalFilename = prepareFilename(newFilename, extension);

                    recording.setOriginalFilename(finalFilename);
                    recordingRepository.save(recording);

                    // Переименовываем файл на диске
                    try {
                        Path oldPath = Paths.get(storagePath, oldFilename);
                        Path newPath = Paths.get(storagePath, finalFilename);
                        if (Files.exists(oldPath)) {
                            Files.move(oldPath, newPath);
                            log.info("File renamed: {} -> {}", oldFilename, finalFilename);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to rename file: {}", e.getMessage());
                    }

                    log.info("Recording renamed successfully: id={}, newFilename={}", id, finalFilename);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean renameRecording(String id, String newFilename, String userId) {
        log.info("Renaming recording: {} to {} for user: {}", id, newFilename, userId);

        return recordingRepository.findById(id)
                .filter(recording -> userId.equals(recording.getUserId()))
                .map(recording -> {
                    String oldFilename = recording.getFilename();
                    String extension = getExtension(oldFilename);

                    // ✅ Подготавливаем финальное имя до использования в лямбде
                    String finalFilename = prepareFilename(newFilename, extension);

                    recording.setOriginalFilename(finalFilename);
                    recordingRepository.save(recording);

                    // Переименовываем файл на диске
                    try {
                        Path oldPath = Paths.get(storagePath, oldFilename);
                        Path newPath = Paths.get(storagePath, finalFilename);
                        if (Files.exists(oldPath)) {
                            Files.move(oldPath, newPath);
                            log.info("File renamed: {} -> {}", oldFilename, finalFilename);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to rename file: {}", e.getMessage());
                    }

                    log.info("Recording renamed successfully: id={}, newFilename={}, userId={}", id, finalFilename, userId);
                    return true;
                })
                .orElse(false);
    }
}