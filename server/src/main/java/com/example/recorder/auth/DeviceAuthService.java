package com.example.recorder.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthService {

    private final ObjectMapper objectMapper;

    @Value("${recorder.device-auth.users-file:./users.json}")
    private String usersFile;

    @Value("${recorder.device-auth.users-file-fallback:./users.json.example}")
    private String usersFileFallback;

    @Value("${recorder.device-auth.token-ttl-seconds:3600}")
    private long tokenTtlSeconds;

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public AuthenticationResult authenticate(String login, String passwordHash) {
        if (login == null || login.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            return AuthenticationResult.failure();
        }

        Map<String, String> users = loadUsers();
        String expectedHash = users.get(login);
        if (expectedHash == null || !expectedHash.equalsIgnoreCase(passwordHash.trim())) {
            return AuthenticationResult.failure();
        }

        long expiresIn = Math.max(tokenTtlSeconds, 60L);
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new SessionInfo(login, expiresAt));
        evictExpiredSessions();

        return new AuthenticationResult(true, login, token, expiresIn);
    }

    public Optional<String> validateAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return validateToken(token);
    }

    public Optional<String> validateToken(String token) {
        SessionInfo session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.login());
    }

    public RemoteCommand currentCommandFor(String login) {
        return new RemoteCommand(false, 0);
    }

    private Map<String, String> loadUsers() {
        for (String location : new String[]{usersFile, usersFileFallback}) {
            Path path = Paths.get(location);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            } catch (IOException e) {
                log.warn("Failed to read device users from {}: {}", path, e.getMessage());
            }
        }

        log.warn("No device auth user file found at {} or {}", usersFile, usersFileFallback);
        return Map.of();
    }

    private void evictExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public record AuthenticationResult(boolean success, String login, String token, long expiresIn) {
        static AuthenticationResult failure() {
            return new AuthenticationResult(false, null, null, 0);
        }
    }

    public record RemoteCommand(boolean recording, long sequence) {
    }

    private record SessionInfo(String login, Instant expiresAt) {
    }
}
