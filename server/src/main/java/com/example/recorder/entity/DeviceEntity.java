package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Сущность устройства (ESP32-C6 и другие).
 * Используется для динамической аутентификации устройств без привязки к пользователю.
 */
@Entity
@Table(name = "devices", uniqueConstraints = {
    @UniqueConstraint(columnNames = "login"),
    @UniqueConstraint(columnNames = "session_token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * Логин устройства (например, MAC-адрес).
     */
    @Column(name = "login", nullable = false, unique = true, length = 100)
    private String login;

    /**
     * Хеш пароля устройства (SHA-256).
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * Связь с пользователем Telegram (NULL для автономных устройств).
     */
    @Column(name = "user_id", length = 36)
    private String userId;

    /**
     * Токен сессии устройства для Bearer-аутентификации.
     */
    @Column(name = "session_token", length = 255, unique = true)
    private String sessionToken;

    /**
     * Время истечения сессии устройства.
     */
    @Column(name = "session_expires_at")
    private LocalDateTime sessionExpiresAt;

    /**
     * Дата и время регистрации устройства.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Дата и время последнего входа устройства.
     */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * Связь с пользователем (для запросов).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserEntity user;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
