package com.example.recorder.repository;

import com.example.recorder.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с пользователями.
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
    
    /**
     * Найти пользователя по Telegram ID.
     */
    @Query(value = "SELECT * FROM users WHERE telegram_id = ?1 LIMIT 1", nativeQuery = true)
    Optional<UserEntity> findByTelegramId(Long telegramId);
    
    /**
     * Найти пользователя по логину.
     */
    @Query(value = "SELECT * FROM users WHERE login = ?1 LIMIT 1", nativeQuery = true)
    List<UserEntity> findByLogin(String login);
    
    /**
     * Найти пользователя по имени пользователя.
     */
    Optional<UserEntity> findByUsername(String username);
    
    /**
     * Найти пользователя по токену сессии.
     */
    @Query("SELECT u FROM UserEntity u WHERE u.sessionToken = :sessionToken")
    Optional<UserEntity> findBySessionToken(String sessionToken);
    
    /**
     * Проверить, существует ли пользователь с таким логином.
     */
    boolean existsByLogin(String login);
    
    /**
     * Проверить, существует ли пользователь с таким Telegram ID.
     */
    boolean existsByTelegramId(Long telegramId);
    
    /**
     * Очистить истекшие сессии.
     */
    @Query("UPDATE UserEntity u SET u.sessionToken = null, u.sessionExpiresAt = null WHERE u.sessionExpiresAt < :now")
    void clearExpiredSessions(@Param("now") LocalDateTime now);
}