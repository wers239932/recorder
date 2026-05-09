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
 * - POST /api/v1/device/auth/login - аутентификация устройства (1:1 с пользователем)
 * - GET  /api/v1/device/command - получение команды для устройства
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceIntegrationController {

    private final DeviceAuthService deviceAuthService;

    /**
     * Аутентификация устройства.
     * Логин и пароль должны совпадать с учётными данными пользователя Telegram.
     * При успешной аутентификации устройство автоматически привязывается к пользователю.
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

    public record LoginRequest(
            String login,
            @JsonProperty("password_hash") String passwordHash) {
    }
}
