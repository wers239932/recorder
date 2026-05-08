package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Сущность пользователя Telegram бота.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(columnNames = "login")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {
    
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    /**
     * Telegram ID пользователя.
     */
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;
    
    /**
     * Имя пользователя в Telegram (@username).
     */
    @Column(name = "username", length = 255, unique = true)
    private String username;
    
    /**
     * Логин для аутентификации.
     */
    @Column(name = "login", nullable = false, unique = true, length = 100)
    private String login;
    
    /**
     * Хеш пароля.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    
    /**
     * Имя пользователя (first_name из Telegram).
     */
    @Column(name = "first_name", length = 255)
    private String firstName;
    
    /**
     * Фамилия пользователя (last_name из Telegram).
     */
    @Column(name = "last_name", length = 255)
    private String lastName;
    
    /**
     * Токен сессии пользователя.
     */
    @Column(name = "session_token", length = 255, unique = true)
    private String sessionToken;
    
    /**
     * Время истечения сессии.
     */
    @Column(name = "session_expires_at")
    private LocalDateTime sessionExpiresAt;
    
    /**
     * Активен ли пользователь.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Дата и время регистрации.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * Дата и время последнего входа.
     */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
    
    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}