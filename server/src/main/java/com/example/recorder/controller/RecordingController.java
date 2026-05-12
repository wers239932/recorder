package com.example.recorder.controller;

import com.example.recorder.auth.DeviceAuthService;
import com.example.recorder.dto.ApiResponse;
import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.RecordingsListResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import com.example.recorder.service.recording.RecordingService;
import com.example.recorder.service.summary.SummaryService;
import com.example.recorder.util.TempMultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST контроллер для управления аудио записями.
 * 
 * API Endpoints:
 * - POST /api/v1/recordings/upload - загрузка WAV-файла
 * - GET  /api/v1/recordings - список записей с пагинацией
 * - GET  /api/v1/recordings/{id} - метаданные записи
 * - GET  /api/v1/recordings/{id}/download - скачивание файла
 * - DELETE /api/v1/recordings/{id} - удаление записи
 * - POST /api/v1/recordings/{id}/summarize - запуск суммаризации
 * - GET  /api/v1/recordings/{id}/summary/status - статус суммаризации
 * - GET  /api/v1/stats - статистика хранилища
 * - GET  /api/v1/health - проверка здоровья
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class RecordingController {
    
    private final RecordingService recordingService;
    private final SummaryService summaryService;
    private final DeviceAuthService deviceAuthService;
    
    /**
     * Загрузка WAV-файла с ESP32-C6 устройства (multipart/form-data).
     * Автоматически запускает суммаризацию (если включено в конфигурации).
     */
    @PostMapping(value = "/recordings/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RecordingResponse>> uploadRecording(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "deviceInfo", required = false, defaultValue = "ESP32-C6-Recorder") 
            String deviceInfo,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String xRealIp) {
        
        String clientIp = getClientIp(xForwardedFor, xRealIp);
        
        UploadRecordingRequest request = UploadRecordingRequest.builder()
            .deviceInfo(deviceInfo)
            .deviceIp(clientIp)
            .filename(file.getOriginalFilename())
            .fileSize(file.getSize())
            .contentType(file.getContentType())
            .build();
        
        try {
            RecordingResponse response = recordingService.uploadRecording(file, request, clientIp);
            
            log.info("Recording uploaded: id={}, size={}", response.getId(), response.getFileSize());
            
            // Добавляем информацию о суммаризации в ответ
            String summaryStatus = recordingService.getSummarizationStatus(response.getId());
            Map<String, String> summarizationInfo = new HashMap<>();
            summarizationInfo.put("status", summaryStatus);
            summarizationInfo.put("autoSummarize", "true");
            
            // Создаём расширенный ответ с информацией о суммаризации
            RecordingResponse responseWithSummary = RecordingResponse.builder()
                .id(response.getId())
                .filename(response.getFilename())
                .originalFilename(response.getOriginalFilename())
                .fileSize(response.getFileSize())
                .contentType(response.getContentType())
                .deviceInfo(response.getDeviceInfo())
                .deviceIp(response.getDeviceIp())
                .status(response.getStatus())
                .createdAt(response.getCreatedAt())
                .downloadUrl(response.getDownloadUrl())
                .build();
            
            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                    "Recording uploaded successfully. Summarization started.", 
                    responseWithSummary,
                    summarizationInfo
                ));
                
        } catch (IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Upload failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed", e);
        }
    }
    
    /**
     * Загрузка WAV-файла с ESP32-C6 устройства (raw body с токеном авторизации).
     * Принимает аудио как raw body с Content-Type: audio/wav.
     * Токен авторизации передаётся в заголовке Authorization: Bearer <token> или X-Device-Token.
     * Автоматически запускает суммаризацию (если включено в конфигурации).
     */
    @PostMapping(value = {"/recordings/upload-raw", "/data/upload"},
                 consumes = "audio/wav",
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RecordingResponse>> uploadRecordingRaw(
            @RequestBody byte[] audioData,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            @RequestHeader(value = "X-Device-Info", required = false, defaultValue = "ESP32-C6-Recorder")
            String deviceInfo,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String xRealIp,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest, xForwardedFor, xRealIp);

        // Аутентификация устройства через Bearer-токен или X-Device-Token
        String deviceLogin = null;
        String userId = null;

        // Пробуем Bearer-токен из Authorization заголовка
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7).trim();
            if (!token.isEmpty()) {
                deviceLogin = deviceAuthService.validateToken(token).orElse(null);
                // Получаем userId, если устройство привязано к пользователю
                userId = deviceAuthService.getUserIdFromToken(token).orElse(null);
            }
        }

        // Если не нашли, пробуем X-Device-Token
        if (deviceLogin == null && deviceToken != null && !deviceToken.isBlank()) {
            deviceLogin = deviceAuthService.validateToken(deviceToken).orElse(null);
            userId = deviceAuthService.getUserIdFromToken(deviceToken).orElse(null);
        }

        if (deviceLogin != null) {
            log.info("Raw upload authenticated as device={} from IP={}, userId={}", deviceLogin, clientIp, userId);
        } else {
            log.info("Raw upload accepted in compatibility mode from IP={}", clientIp);
        }

        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("Audio data is empty");
        }

        // Создаём временный MultipartFile из raw данных
        TempMultipartFile tempFile = new TempMultipartFile(audioData, "recording.wav", "audio/wav");

        UploadRecordingRequest request = UploadRecordingRequest.builder()
            .deviceInfo(deviceInfo)
            .deviceIp(clientIp)
            .filename("recording.wav")
            .fileSize((long) audioData.length)
            .contentType("audio/wav")
            .build();

        try {
            // Загрузка с deviceLogin для устройств
            // userId будет null, если устройство не привязано к пользователю
            RecordingResponse response = recordingService.uploadRecording(tempFile, request, clientIp, userId, deviceLogin);

            log.info("Recording uploaded (raw): id={}, size={}, deviceLogin={}, userId={}", response.getId(), response.getFileSize(), deviceLogin, userId);

            // Добавляем информацию о транскрипции в ответ
            String transcriptionStatus = recordingService.getTranscriptionStatus(response.getId());
            Map<String, String> processingInfo = new HashMap<>();
            processingInfo.put("transcriptionStatus", transcriptionStatus);
            processingInfo.put("autoTranscribe", "true");
            processingInfo.put("autoSummarize", "true");

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                    "Recording uploaded successfully. Transcription started.",
                    response,
                    processingInfo
                ));

        } catch (IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Raw upload failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed", e);
        }
    }
    
    /**
     * Получение списка всех записей с пагинацией.
     */
    @GetMapping("/recordings")
    public ResponseEntity<ApiResponse<RecordingsListResponse>> getAllRecordings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("asc") 
            ? Sort.by(sortBy).ascending() 
            : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<RecordingResponse> recordingsPage = recordingService.getAllRecordings(pageable);
        
        RecordingsListResponse response = RecordingsListResponse.fromPage(
            recordingsPage.getContent(),
            (int) recordingsPage.getTotalElements(),
            recordingsPage.getNumber(),
            recordingsPage.getSize(),
            recordingsPage.getTotalPages()
        );
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Получение метаданных записи по ID.
     */
    @GetMapping("/recordings/{id}")
    public ResponseEntity<ApiResponse<RecordingResponse>> getRecording(@PathVariable String id) {
        return recordingService.getRecordingById(id)
            .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found: " + id));
    }
    
    /**
     * Скачивание WAV-файла записи.
     */
    @GetMapping("/recordings/{id}/download")
    public ResponseEntity<Resource> downloadRecording(@PathVariable String id) {
        Path filePath = recordingService.getRecordingFilePath(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found: " + id));
        
        if (!Files.exists(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found on disk");
        }
        
        RecordingResponse recording = recordingService.getRecordingById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found"));
        
        Resource resource = new FileSystemResource(filePath.toFile());
        String encodedFilename = URLEncoder.encode(recording.getOriginalFilename() != null 
                ? recording.getOriginalFilename() : recording.getFilename(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename*=UTF-8''" + encodedFilename)
            .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(recording.getFileSize()))
            .body(resource);
    }
    
    /**
     * Удаление записи по ID.
     */
    @DeleteMapping("/recordings/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRecording(@PathVariable String id) {
        boolean deleted = recordingService.deleteRecording(id);
        
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found: " + id);
        }
        
        return ResponseEntity.ok(ApiResponse.success("Recording deleted successfully", null));
    }
    
    /**
     * Запуск суммаризации для записи.
     */
    @PostMapping("/recordings/{id}/summarize")
    public ResponseEntity<ApiResponse<Map<String, String>>> startSummarization(
            @PathVariable String id,
            @RequestParam(value = "language", required = false) String language) {
        
        try {
            recordingService.startSummarization(id, language);
            
            Map<String, String> data = new HashMap<>();
            data.put("recordingId", id);
            data.put("status", "PENDING");
            data.put("message", "Summarization started");
            
            return ResponseEntity.ok(ApiResponse.success(data));
            
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
    
    /**
     * Получение статуса суммаризации.
     */
    @GetMapping("/recordings/{id}/summary/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> getSummarizationStatus(
            @PathVariable String id) {
        
        String status = recordingService.getSummarizationStatus(id);
        
        Map<String, String> data = new HashMap<>();
        data.put("recordingId", id);
        data.put("status", status);
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Статистика хранилища.
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        long[] stats = recordingService.getStorageStats();
        
        Map<String, Object> data = new HashMap<>();
        data.put("recordingsCount", stats[0]);
        data.put("totalSizeBytes", stats[1]);
        data.put("totalSizeMB", String.format("%.2f", stats[1] / (1024.0 * 1024.0)));
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Проверка здоровья сервера.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> healthCheck() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", java.time.LocalDateTime.now().toString());
        data.put("service", "recorder-server");
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Определение IP адреса клиента.
     */
    private String getClientIp(HttpServletRequest request, String xForwardedFor, String xRealIp) {
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request != null ? request.getRemoteAddr() : "unknown";
    }

    private String getClientIp(String xForwardedFor, String xRealIp) {
        return getClientIp(null, xForwardedFor, xRealIp);
    }

    private String resolveAuthenticatedDevice(String authorizationHeader, String deviceToken) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            return deviceAuthService.validateAuthorizationHeader(authorizationHeader).orElse(null);
        }
        if (deviceToken != null && !deviceToken.isBlank()) {
            return "legacy-device";
        }
        return null;
    }
}
