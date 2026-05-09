package com.example.recorder.auth;

import com.example.recorder.entity.DeviceEntity;
import com.example.recorder.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис аутентификации устройств.
 * Поддерживает динамическую регистрацию устройств при первом входе.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthService {

    private final DeviceRepository deviceRepository;

    @Value("${recorder.device-auth.token-ttl-hours:24}")
    private int tokenTtlHours;

    /**
     * Аутентификация устройства с динамической регистрацией.
     * Если устройство не найдено — создаётся автоматически.
     *
     * @param login логин устройства (например, MAC-адрес)
     * @param passwordHash хеш пароля устройства (SHA-256)
     * @return результат аутентификации
     */
    @Transactional
    public AuthenticationResult authenticate(String login, String passwordHash) {
        if (login == null || login.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            log.warn("Authentication failed: empty login or passwordHash");
            return AuthenticationResult.failure();
        }

        log.info("Authenticating device: login={}", login);

        // Пытаемся найти существующее устройство
        Optional<DeviceEntity> deviceOpt = deviceRepository.findFirstByLogin(login);

        DeviceEntity device;
        if (deviceOpt.isPresent()) {
            device = deviceOpt.get();
            // Проверяем пароль
            if (!device.getPasswordHash().equalsIgnoreCase(passwordHash.trim())) {
                log.warn("Device {} authentication failed: invalid password", login);
                return AuthenticationResult.failure();
            }
            log.info("Found existing device: id={}, userId={}", device.getId(), device.getUserId());
        } else {
            // Автоматическая регистрация нового устройства
            device = DeviceEntity.builder()
                    .login(login)
                    .passwordHash(passwordHash)
                    .build();
            device = deviceRepository.save(device);
            log.info("Auto-registered new device: id={}, login={}", device.getId(), login);
        }

        // Генерируем токен сессии
        String token = UUID.randomUUID().toString().replace("-", "");
        long expiresIn = tokenTtlHours * 3600L;
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);

        device.setSessionToken(token);
        device.setSessionExpiresAt(LocalDateTime.ofInstant(expiresAt, java.time.ZoneId.systemDefault()));
        device.setLastLoginAt(LocalDateTime.now());
        deviceRepository.save(device);

        log.info("Device {} authenticated successfully, token expires in {} hours", login, tokenTtlHours);

        return new AuthenticationResult(true, login, token, expiresIn);
    }

    /**
     * Проверка заголовка Authorization: Bearer <token>.
     */
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

    /**
     * Проверка токена сессии устройства.
     * Возвращает Optional с логином устройства.
     */
    @Transactional(readOnly = true)
    public Optional<String> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return deviceRepository.findBySessionToken(token)
                .filter(this::isSessionValid)
                .map(DeviceEntity::getLogin);
    }

    /**
     * Проверка токена сессии устройства с возвратом информации об устройстве.
     * Возвращает Optional с DeviceEntity для получения userId.
     */
    @Transactional(readOnly = true)
    public Optional<DeviceEntity> validateTokenWithDevice(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return deviceRepository.findBySessionToken(token)
                .filter(this::isSessionValid);
    }

    /**
     * Получение userId по токену устройства.
     * Возвращает userId, если устройство привязано к пользователю.
     */
    @Transactional(readOnly = true)
    public Optional<String> getUserIdFromToken(String token) {
        return validateTokenWithDevice(token)
                .map(DeviceEntity::getUserId);
    }

    /**
     * Проверка валидности сессии устройства.
     */
    private boolean isSessionValid(DeviceEntity device) {
        if (device.getSessionToken() == null || device.getSessionExpiresAt() == null) {
            return false;
        }
        return device.getSessionExpiresAt().isAfter(LocalDateTime.now());
    }

    /**
     * Получение текущей команды для устройства.
     * В будущем можно хранить команды в БД.
     */
    public RemoteCommand currentCommandFor(String login) {
        // Пока возвращаем пустую команду
        return new RemoteCommand(false, 0);
    }

    /**
     * Привязка устройства к пользователю.
     */
    @Transactional
    public boolean linkDeviceToUser(String deviceLogin, String userId) {
        return deviceRepository.findFirstByLogin(deviceLogin)
                .map(device -> {
                    device.setUserId(userId);
                    deviceRepository.save(device);
                    log.info("Device {} linked to user {}", deviceLogin, userId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Отвязка устройства от пользователя.
     */
    @Transactional
    public boolean unlinkDevice(String deviceLogin) {
        return deviceRepository.findFirstByLogin(deviceLogin)
                .map(device -> {
                    device.setUserId(null);
                    deviceRepository.save(device);
                    log.info("Device {} unlinked from user", deviceLogin);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Получение списка устройств пользователя.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> getDevicesByUser(String userId) {
        return deviceRepository.findByUserId(userId)
                .stream()
                .map(DeviceEntity::getLogin)
                .toList();
    }

    public record AuthenticationResult(boolean success, String login, String token, long expiresIn) {
        static AuthenticationResult failure() {
            return new AuthenticationResult(false, null, null, 0);
        }
    }

    public record RemoteCommand(boolean recording, long sequence) {
    }
}
