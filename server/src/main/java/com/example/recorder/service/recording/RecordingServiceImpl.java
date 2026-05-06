package com.example.recorder.service.recording;

import com.example.recorder.config.SummaryClientProperties;
import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.SummaryEntity;
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
import java.util.Optional;
import java.util.UUID;

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
    
    @Override
    @Transactional
    public RecordingResponse uploadRecording(
            MultipartFile file,
            UploadRecordingRequest request,
            String clientIp) throws IOException {
        
        log.info("Uploading recording: filename={}, size={}", 
            request.getFilename(), file.getSize());
        
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
            .filename(filename)
            .originalFilename(request.getFilename())
            .fileSize(file.getSize())
            .contentType(file.getContentType() != null ? file.getContentType() : "audio/wav")
            .deviceInfo(request.getDeviceInfo())
            .deviceIp(clientIp)
            .status(RecordingEntity.RecordingStatus.UPLOADED)
            .build();
        
        recordingRepository.save(recording);
        
        log.info("Recording uploaded successfully: id={}, path={}", recording.getId(), filename);
        
        // Автоматический запуск суммаризации (если включено в конфигурации)
        if (Boolean.TRUE.equals(summaryProperties.autoSummarize())) {
            startSummarization(recording.getId(), null);
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
            log.warn("Failed to delete file {}: {}", filePath, e.getMessage());
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
    @Transactional(readOnly = true)
    public long[] getStorageStats() {
        long count = recordingRepository.count();
        long totalSize = recordingRepository.findAll().stream()
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
        
        // Проверяем, нет ли уже суммаризации
        if (summaryRepository.existsByRecordingId(recordingId)) {
            log.warn("Summarization already exists for recording: {}", recordingId);
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
        summaryService.summarizeAsync(recordingId, language);
    }
    
    @Override
    public String getSummarizationStatus(String recordingId) {
        var status = summaryService.getStatus(recordingId);
        return status.name();
    }
    
    /**
     * Валидация загружаемого файла.
     */
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
    
    /**
     * Получение расширения файла.
     */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return ".wav";
        }
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }
}
