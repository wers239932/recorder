package com.example.recorder.controller;

import com.example.recorder.auth.DeviceAuthService;
import com.example.recorder.dto.ApiResponse;
import com.example.recorder.dto.RecordingResponse;
import com.example.recorder.dto.RecordingsListResponse;
import com.example.recorder.dto.SummaryResponse;
import com.example.recorder.dto.TranscriptionResponse;
import com.example.recorder.dto.UploadRecordingRequest;
import com.example.recorder.entity.SummaryEntity;
import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.UserEntity;
import com.example.recorder.repository.SummaryRepository;
import com.example.recorder.service.recording.RecordingService;
import com.example.recorder.service.summary.SummaryService;
import com.example.recorder.service.transcription.TranscriptionService;
import com.example.recorder.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST контроллер для Telegram бота.
 *
 * API Endpoints:
 * - POST /api/v1/auth/register - регистрация нового пользователя
 * - POST /api/v1/auth/login - вход в аккаунт
 * - POST /api/v1/auth/logout - выход из аккаунта
 * - POST /api/v1/bot/recordings/upload - загрузка записи через бота (multipart)
 * - GET  /api/v1/bot/recordings - список записей пользователя
 * - GET  /api/v1/bot/recordings/{id} - метаданные записи пользователя
 * - GET  /api/v1/bot/recordings/{id}/download - скачивание файла
 * - DELETE /api/v1/bot/recordings/{id} - удаление записи
 * - PUT  /api/v1/bot/recordings/{id}/rename - переименование записи
 * - POST /api/v1/bot/recordings/{id}/summarize - запуск суммаризации
 * - POST /api/v1/bot/recordings/{id}/transcribe - запуск расшифровки
 * - GET  /api/v1/bot/recordings/{id}/transcription - получение расшифровки
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class BotController {

    private final UserService userService;
    private final RecordingService recordingService;
    private final TranscriptionService transcriptionService;
    private final DeviceAuthService deviceAuthService;
    private final SummaryRepository summaryRepository;
    
    /**
     * Регистрация нового пользователя.
     */
    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerUser(
            @Valid @RequestBody RegisterRequest request) {
        
        try {
            UserEntity user = userService.registerUser(
                    request.telegramId(),
                    request.username(),
                    request.login(),
                    request.passwordHash(),
                    request.firstName(),
                    request.lastName()
            );
            
            Map<String, Object> data = new HashMap<>();
            data.put("userId", user.getId());
            data.put("login", user.getLogin());
            data.put("message", "Пользователь успешно зарегистрирован");
            
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.success(data));
                    
        } catch (IllegalArgumentException e) {
            log.warn("Registration failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    /**
     * Аутентификация пользователя.
     */
    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> loginUser(
            @Valid @RequestBody LoginRequest request) {
        
        Optional<String> token = userService.authenticate(request.login(), request.passwordHash());
        
        return token.map(t -> {
            Map<String, Object> data = new HashMap<>();
            data.put("token", t);
            data.put("message", "Авторизация успешна");
            
            return ResponseEntity.ok(ApiResponse.success(data));
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль"));
    }
    
    /**
     * Выход из системы.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logoutUser(
            @RequestHeader("Authorization") String authorizationHeader) {
        
        String token = extractToken(authorizationHeader);
        boolean loggedOut = userService.logout(token);
        
        if (!loggedOut) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный токен");
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Выход из системы выполнен");
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Загрузка WAV-файла через Telegram бота (multipart/form-data).
     * Автоматически привязывает запись к авторизованному пользователю.
     */
    @PostMapping(value = "/bot/recordings/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RecordingResponse>> uploadRecording(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "deviceInfo", required = false, defaultValue = "Telegram-Bot")
            String deviceInfo,
            @RequestHeader("Authorization") String authorizationHeader,
            HttpServletRequest request) {

        UserEntity user = authenticateUser(authorizationHeader);

        String clientIp = request.getRemoteAddr();

        UploadRecordingRequest uploadRequest = UploadRecordingRequest.builder()
                .deviceInfo(deviceInfo)
                .deviceIp(clientIp)
                .filename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .build();

        try {
            RecordingResponse response = recordingService.uploadRecordingForUser(
                    file, uploadRequest, clientIp, user.getId());

            log.info("Recording uploaded via bot: userId={}, id={}, size={}", 
                    user.getId(), response.getId(), response.getFileSize());

            // Добавляем информацию о суммаризации в ответ
            String summaryStatus = recordingService.getSummarizationStatus(response.getId());
            Map<String, String> summarizationInfo = new HashMap<>();
            summarizationInfo.put("status", summaryStatus);
            summarizationInfo.put("autoSummarize", "true");

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.success(
                            "Recording uploaded successfully. Summarization started.",
                            response,
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
     * Получение списка записей пользователя.
     */
    @GetMapping("/bot/recordings")
    public ResponseEntity<ApiResponse<List<RecordingResponse>>> getUserRecordings(
            @RequestHeader("Authorization") String authorizationHeader) {

        UserEntity user = authenticateUser(authorizationHeader);

        List<RecordingResponse> recordings = recordingService.getAllUserRecordings(user.getId());

        return ResponseEntity.ok(ApiResponse.success(recordings));
    }

    /**
     * Получение метаданных записи пользователя.
     */
    @GetMapping("/bot/recordings/{id}")
    public ResponseEntity<ApiResponse<RecordingResponse>> getRecording(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorizationHeader) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        return recordingService.getRecordingById(id, user.getId())
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"));
    }
    
    /**
     * Скачивание файла записи.
     */
    @GetMapping("/bot/recordings/{id}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadRecording(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorizationHeader,
            HttpServletRequest request) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        java.nio.file.Path filePath = recordingService.getRecordingFilePath(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"));
        
        if (!java.nio.file.Files.exists(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден на диске");
        }
        
        RecordingResponse recording = recordingService.getRecordingById(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"));
        
        org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(filePath.toFile());
        String encodedFilename = java.net.URLEncoder.encode(
                recording.getOriginalFilename() != null ? recording.getOriginalFilename() : recording.getFilename(),
                java.nio.charset.StandardCharsets.UTF_8
        ).replace("+", "%20");
        
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "audio/wav")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(recording.getFileSize()))
                .body(resource);
    }
    
    /**
     * Удаление записи.
     */
    @DeleteMapping("/bot/recordings/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteRecording(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorizationHeader) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        boolean deleted = recordingService.deleteRecording(id, user.getId());
        
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена");
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Запись удалена");
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Переименование записи.
     */
    @PutMapping("/bot/recordings/{id}/rename")
    public ResponseEntity<ApiResponse<Map<String, Object>>> renameRecording(
            @PathVariable String id,
            @Valid @RequestBody RenameRequest request,
            @RequestHeader("Authorization") String authorizationHeader) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        boolean renamed = recordingService.renameRecording(id, request.newFilename(), user.getId());
        
        if (!renamed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена");
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Запись переименована");
        data.put("newFilename", request.newFilename());
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }
    
    /**
     * Запуск суммаризации записи.
     */
    @PostMapping("/bot/recordings/{id}/summarize")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startSummarization(
            @PathVariable String id,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader("Authorization") String authorizationHeader) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        try {
            recordingService.startSummarization(id, language, user.getId());
            
            Map<String, Object> data = new HashMap<>();
            data.put("recordingId", id);
            data.put("status", "PENDING");
            data.put("message", "Суммаризация запущена");
            
            return ResponseEntity.ok(ApiResponse.success(data));
            
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    /**
     * Запуск расшифровки записи.
     */
    @PostMapping("/bot/recordings/{id}/transcribe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startTranscription(
            @PathVariable String id,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader("Authorization") String authorizationHeader) {
        
        UserEntity user = authenticateUser(authorizationHeader);
        
        // Проверяем, что запись принадлежит пользователю
        if (!recordingService.getRecordingById(id, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена");
        }
        
        try {
            transcriptionService.startTranscription(id, language);
            
            Map<String, Object> data = new HashMap<>();
            data.put("recordingId", id);
            data.put("status", "PROCESSING");
            data.put("message", "Расшифровка запущена");
            
            return ResponseEntity.ok(ApiResponse.success(data));
            
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    /**
     * Получение расшифровки записи.
     */
    @GetMapping("/bot/recordings/{id}/transcription")
    public ResponseEntity<ApiResponse<TranscriptionResponse>> getTranscription(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorizationHeader) {

        UserEntity user = authenticateUser(authorizationHeader);

        // Проверяем, что запись принадлежит пользователю
        if (!recordingService.getRecordingById(id, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена");
        }

        return transcriptionService.getTranscriptionByRecordingId(id)
                .map(this::convertToResponse)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Расшифровка не найдена"));
    }

    /**
     * Получение суммаризации записи.
     */
    @GetMapping("/bot/recordings/{id}/summary")
    public ResponseEntity<ApiResponse<SummaryResponse>> getSummarization(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorizationHeader) {

        UserEntity user = authenticateUser(authorizationHeader);

        // Проверяем, что запись принадлежит пользователю
        if (!recordingService.getRecordingById(id, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена");
        }

        return summaryRepository.findByRecordingId(id)
                .map(SummaryResponse::fromEntity)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Суммаризация не найдена"));
    }
    
    // Вспомогательные методы

    /**
     * Список устройств пользователя.
     * В модели 1:1 возвращает логин пользователя (устройство автоматически привязывается при первой аутентификации).
     */
    @GetMapping("/bot/devices")
    public ResponseEntity<ApiResponse<List<String>>> getUserDevices(
            @RequestHeader("Authorization") String authorizationHeader) {

        UserEntity user = authenticateUser(authorizationHeader);

        List<String> devices = deviceAuthService.getDevicesByUser(user.getId());

        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    private UserEntity authenticateUser(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        return userService.validateToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный или истекший токен"));
    }
    
    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется заголовок Authorization");
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный формат токена");
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        if (token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пустой токен");
        }
        return token;
    }
    
    private TranscriptionResponse convertToResponse(TranscriptionEntity entity) {
        return TranscriptionResponse.builder()
                .id(entity.getId())
                .recordingId(entity.getRecording().getId())
                .transcriptionText(entity.getTranscriptionText())
                .briefText(entity.getBriefText())
                .detectedLanguage(entity.getDetectedLanguage())
                .confidenceScore(entity.getConfidenceScore())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
    
    // DTO классы
    
    public record RegisterRequest(
            Long telegramId,
            String username,
            String login,
            String passwordHash,
            String firstName,
            String lastName
    ) {}
    
    public record LoginRequest(
            String login,
            String passwordHash
    ) {}
    
    public record RenameRequest(
            String newFilename
    ) {}
}