package com.example.recorder.auth;

import com.example.recorder.entity.DeviceEntity;
import com.example.recorder.entity.UserEntity;
import com.example.recorder.repository.DeviceRepository;
import com.example.recorder.repository.UserRepository;
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
 * 
 * Модель 1:1 — устройство использует те же логин/пароль, что и пользователь Telegram.
 * При первой аутентификации устройство автоматически привязывается к пользователю.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    @Value("${recorder.device-auth.token-ttl-hours:24}")
    private int tokenTtlHours;

    /**
     * Аутентификация устройства с использованием credentials пользователя.
     * 
     * Логин и пароль должны совпадать с учётными данными пользователя Telegram.
     * При успешной аутентификации устройство автоматически привязывается к пользователю.
     *
     * @param login логин устройства (должен совпадать с логином пользователя)
     * @param passwordHash хеш пароля (должен совпадать с passwordHash пользователя)
     * @return результат аутентификации
     */
    @Transactional
    public AuthenticationResult authenticate(String login, String passwordHash) {
        if (login == null || login.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            log.warn("Authentication failed: empty login or passwordHash");
            return AuthenticationResult.failure();
        }

        log.info("Authenticating device: login={}", login);

        // Ищем пользователя по логину и passwordHash
        Optional<UserEntity> userOpt = userRepository.findByLoginAndPasswordHash(login, passwordHash);
        
        if (userOpt.isEmpty()) {
            log.warn("Device authentication failed: user not found or invalid password for login={}", login);
            return AuthenticationResult.failure();
        }

        UserEntity user = userOpt.get();
        
        // Проверяем, что пользователь активен
        if (!user.getIsActive()) {
            log.warn("Device authentication failed: user {} is inactive", login);
            return AuthenticationResult.failure();
        }

        // Проверяем, есть ли уже привязанное устройство
        Optional<DeviceEntity> existingDevice = deviceRepository.findFirstByLogin(login);
        
        if (existingDevice.isPresent()) {
            // Устройство уже привязано — обновляем токен
            DeviceEntity device = existingDevice.get();
            
            // Проверяем, что устройство привязано к тому же пользователю
            if (!user.getId().equals(device.getUserId())) {
                log.warn("Device {} is linked to different user", login);
                return AuthenticationResult.failure();
            }
            
            log.info("Device {} re-authenticated for user {}", login, user.getId());
        } else {
            // Создаём новую запись устройства
            DeviceEntity device = DeviceEntity.builder()
                    .login(login)
                    .passwordHash(passwordHash)
                    .userId(user.getId())
                    .build();
            device = deviceRepository.save(device);
            log.info("Device {} auto-linked to user {}", login, user.getId());
        }

        // Генерируем токен сессии
        String token = UUID.randomUUID().toString().replace("-", "");
        long expiresIn = tokenTtlHours * 3600L;
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);

        // Обновляем токен в устройстве
        deviceRepository.findFirstByLogin(login).ifPresent(device -> {
            device.setSessionToken(token);
            device.setSessionExpiresAt(LocalDateTime.ofInstant(expiresAt, java.time.ZoneId.systemDefault()));
            device.setLastLoginAt(LocalDateTime.now());
            deviceRepository.save(device);
        });

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
     * Получение списка устройств пользователя.
     * В модели 1:1 возвращает логин пользователя (если устройство привязано).
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
