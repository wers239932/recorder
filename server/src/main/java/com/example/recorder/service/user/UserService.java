package com.example.recorder.service.user;

import com.example.recorder.entity.UserEntity;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Сервис для работы с пользователями.
 */
public interface UserService {
    
    /**
     * Регистрация нового пользователя.
     */
    UserEntity registerUser(Long telegramId, String username, String login, String passwordHash, 
                           String firstName, String lastName);
    
    /**
     * Аутентификация пользователя по логину и паролю.
     */
    Optional<String> authenticate(String login, String passwordHash);
    
    /**
     * Валидация токена сессии.
     */
    Optional<UserEntity> validateToken(String token);
    
    /**
     * Выход пользователя из системы (удаление токена).
     */
    boolean logout(String token);
    
    /**
     * Найти пользователя по Telegram ID.
     */
    Optional<UserEntity> findByTelegramId(Long telegramId);
    
    /**
     * Найти пользователя по логину.
     */
    Optional<UserEntity> findByLogin(String login);
    
    /**
     * Проверить, существует ли пользователь с таким логином.
     */
    boolean existsByLogin(String login);
    
    /**
     * Проверить, существует ли пользователь с таким Telegram ID.
     */
    boolean existsByTelegramId(Long telegramId);
    
    /**
     * Обновление времени последнего входа.
     */
    void updateLastLogin(Long telegramId);
}