package com.example.recorder.controller;

import com.example.recorder.dto.ApiResponse;
import com.example.recorder.entity.UserEntity;
import com.example.recorder.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceIntegrationController {

    private final UserService userService;

    @PostMapping("/device/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginRequest request) {
        Optional<String> token = userService.authenticate(request.login(), request.password());
        
        if (token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid device credentials");
        }

        UserEntity user = userService.findByLogin(request.login())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        log.info("Device authenticated: {}", user.getLogin());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "token", token.get(),
            "expires_in", 24 * 3600 // 24 hours in seconds
        )));
    }

    @GetMapping("/device/command")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCommand(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        UserEntity user = userService.validateToken(token)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token"));

        // Возвращаем пустую команду (устройство может начать запись по своему усмотрению)
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "recording", false,
            "sequence", 0
        )));
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization header format");
        }
        return authorizationHeader.substring(prefix.length()).trim();
    }

    public record LoginRequest(
            String login,
            String password) {
    }
}
