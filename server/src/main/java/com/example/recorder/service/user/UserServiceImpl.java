package com.example.recorder.service.user;

import com.example.recorder.entity.UserEntity;
import com.example.recorder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Реализация сервиса для работы с пользователями.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${recorder.user.session-ttl-hours:24}")
    private int sessionTtlHours;
    
    @Override
    @Transactional
    public UserEntity registerUser(Long telegramId, String username, String login, String passwordHash,
                                  String firstName, String lastName) {
        if (userRepository.existsByLogin(login)) {
            throw new IllegalArgumentException("Пользователь с таким логином уже существует");
        }

        // Нормализуем username: пустая строка -> null
        if (username != null && username.isBlank()) {
            username = null;
        }

        // Убираем проверку уникальности username — это не критично для функционала
        // username используется только для отображения информации

        UserEntity user = UserEntity.builder()
                .telegramId(telegramId)
                .username(username)
                .login(login)
                .passwordHash(passwordHash)
                .firstName(firstName)
                .lastName(lastName)
                .isActive(true)
                .build();

        UserEntity savedUser = userRepository.save(user);
        log.info("Зарегистрирован новый пользователь: login={}, telegramId={}", login, telegramId);

        return savedUser;
    }
    
    @Override
    @Transactional
    public Optional<String> authenticate(String login, String passwordHash) {
        // Используем чистый JDBC для обхода проблемы с Hibernate
        String sql = "SELECT id, telegram_id, is_active FROM users WHERE login = ? AND password_hash = ? LIMIT 1";
        
        log.info("Authenticating user: login={}", login);
        try {
            List<UserRow> results = jdbcTemplate.query(sql, (rs, rowNum) -> new UserRow(
                rs.getString("id"),
                rs.getLong("telegram_id"),
                rs.getBoolean("is_active")
            ), login, passwordHash);
            
            log.info("JDBC query returned {} results", results.size());
            if (results.isEmpty()) {
                log.info("No user found for login: {}", login);
                return Optional.empty();
            }
            
            UserRow row = results.get(0);
            log.info("Found user: id={}, telegramId={}, isActive={}", row.id, row.telegramId, row.isActive);
            if (!row.isActive) {
                log.info("User is not active: {}", login);
                return Optional.empty();
            }
            
            UserEntity user = userRepository.findById(row.id).orElseThrow();
            String token = UUID.randomUUID().toString().replace("-", "");
            user.setSessionToken(token);
            user.setSessionExpiresAt(LocalDateTime.now().plusHours(sessionTtlHours));
            userRepository.save(user);
            updateLastLogin(user.getTelegramId());
            log.info("Пользователь {} успешно вошёл в систему", user.getLogin());
            return Optional.of(token);
        } catch (Exception e) {
            log.error("Authentication failed for login: {}: {}", login, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    // Внутренний класс для маппинга результатов JDBC
    private static class UserRow {
        String id;
        Long telegramId;
        boolean isActive;
        
        UserRow(String id, Long telegramId, boolean isActive) {
            this.id = id;
            this.telegramId = telegramId;
            this.isActive = isActive;
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<UserEntity> validateToken(String token) {
        return userRepository.findBySessionToken(token)
                .filter(user -> user.getIsActive() && 
                                user.getSessionExpiresAt() != null && 
                                user.getSessionExpiresAt().isAfter(LocalDateTime.now()));
    }
    
    @Override
    @Transactional
    public boolean logout(String token) {
        return userRepository.findBySessionToken(token)
                .map(user -> {
                    user.setSessionToken(null);
                    user.setSessionExpiresAt(null);
                    userRepository.save(user);
                    log.info("Пользователь {} вышел из системы", user.getLogin());
                    return true;
                })
                .orElse(false);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<UserEntity> findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<UserEntity> findByLogin(String login) {
        List<UserEntity> users = userRepository.findByLogin(login);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }
    
    @Override
    @Transactional(readOnly = true)
    public boolean existsByLogin(String login) {
        return userRepository.existsByLogin(login);
    }
    
    @Override
    @Transactional(readOnly = true)
    public boolean existsByTelegramId(Long telegramId) {
        return userRepository.existsByTelegramId(telegramId);
    }
    
    @Override
    @Transactional
    public void updateLastLogin(Long telegramId) {
        userRepository.findByTelegramId(telegramId)
                .ifPresent(user -> {
                    user.setLastLoginAt(LocalDateTime.now());
                    userRepository.save(user);
                });
    }
}