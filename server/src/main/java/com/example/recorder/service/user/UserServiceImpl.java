package com.example.recorder.service.user;

import com.example.recorder.entity.UserEntity;
import com.example.recorder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    
    @Value("${recorder.user.session-ttl-hours:24}")
    private int sessionTtlHours;
    
    @Override
    @Transactional
    public UserEntity registerUser(Long telegramId, String username, String login, String passwordHash,
                                  String firstName, String lastName) {
        if (userRepository.existsByLogin(login)) {
            throw new IllegalArgumentException("Пользователь с таким логином уже существует");
        }
        
        // Проверяем, существует ли пользователь с таким username
        if (username != null && userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Пользователь с таким username уже существует");
        }
        
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
    @Transactional(readOnly = true)
    public Optional<String> authenticate(String login, String passwordHash) {
        return userRepository.findByLogin(login)
                .filter(user -> user.getIsActive() && user.getPasswordHash().equals(passwordHash))
                .map(user -> {
                    String token = createSessionToken(user.getTelegramId());
                    updateLastLogin(user.getTelegramId());
                    return token;
                });
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
        return userRepository.findByLogin(login);
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
    
    @Override
    @Transactional
    public String createSessionToken(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(user -> {
                    // Создаем уникальный токен
                    String token = UUID.randomUUID().toString().replace("-", "");
                    
                    // Устанавливаем токен и время истечения
                    user.setSessionToken(token);
                    user.setSessionExpiresAt(LocalDateTime.now().plusHours(sessionTtlHours));
                    
                    userRepository.save(user);
                    log.debug("Создан токен сессии для пользователя {}", user.getLogin());
                    
                    return token;
                })
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
    }
}