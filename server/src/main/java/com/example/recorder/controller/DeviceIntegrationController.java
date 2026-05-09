package com.example.recorder.controller;

import com.example.recorder.auth.DeviceAuthService;
import com.example.recorder.dto.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Контроллер для интеграции с устройствами (ESP32-C6 и другие).
 *
 * API Endpoints:
 * - POST /api/v1/device/auth/login - аутентификация устройства
 * - GET  /api/v1/device/command - получение команды для устройства
 * - POST /api/v1/device/link/{deviceLogin} - привязка устройства к пользователю
 * - DELETE /api/v1/device/unlink/{deviceLogin} - отвязка устройства
 * - GET  /api/v1/device/list - список устройств пользователя
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceIntegrationController {

    private final DeviceAuthService deviceAuthService;

    /**
     * Аутентификация устройства.
     * Если устройство не найдено — создаётся автоматически.
     */
    @PostMapping("/device/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginRequest request) {
        DeviceAuthService.AuthenticationResult auth =
            deviceAuthService.authenticate(request.login(), request.passwordHash());
        if (!auth.success()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid device credentials");
        }

        log.info("Device authenticated: {}", auth.login());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "token", auth.token(),
            "expires_in", auth.expiresIn()
        )));
    }

    /**
     * Получение команды для устройства (polling).
     * Требует Bearer-токен в заголовке Authorization.
     */
    @GetMapping("/device/command")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCommand(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String login = deviceAuthService.validateAuthorizationHeader(authorizationHeader)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token"));

        DeviceAuthService.RemoteCommand command = deviceAuthService.currentCommandFor(login);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "recording", command.recording(),
            "sequence", command.sequence()
        )));
    }

    /**
     * Привязка устройства к пользователю.
     * Требует Bearer-токен пользователя в заголовке Authorization.
     */
    @PostMapping("/device/link/{deviceLogin}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkDevice(
            @PathVariable String deviceLogin,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        String userToken = extractBearerToken(authorizationHeader);
        // Здесь нужна валидация токена пользователя через UserService
        // Для простоты передаём login как идентификатор (в реальной реализации нужно валидировать токен)
        // TODO: добавить валидацию токена пользователя

        boolean linked = deviceAuthService.linkDeviceToUser(deviceLogin, "pending-user-id");
        if (!linked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceLogin);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("message", "Device linked successfully");
        data.put("deviceLogin", deviceLogin);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Отвязка устройства от пользователя.
     */
    @DeleteMapping("/device/unlink/{deviceLogin}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlinkDevice(
            @PathVariable String deviceLogin,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        boolean unlinked = deviceAuthService.unlinkDevice(deviceLogin);
        if (!unlinked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceLogin);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("message", "Device unlinked successfully");
        data.put("deviceLogin", deviceLogin);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Получение списка устройств пользователя.
     */
    @GetMapping("/device/list")
    public ResponseEntity<ApiResponse<List<String>>> getDevices(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        String userToken = extractBearerToken(authorizationHeader);
        // TODO: извлечь userId из токена пользователя
        List<String> devices = deviceAuthService.getDevicesByUser("pending-user-id");

        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }
        return authorizationHeader.substring(7).trim();
    }

    public record LoginRequest(
            String login,
            @JsonProperty("password_hash") String passwordHash) {
    }
}
