package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Сущность текстовой расшифровки аудиозаписи.
 */
@Entity
@Table(name = "transcriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptionEntity {
    
    /**
     * Уникальный идентификатор расшифровки.
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    /**
     * Связанная аудиозапись.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_id", nullable = false, unique = true)
    private RecordingEntity recording;
    
    /**
     * Полный текст расшифровки.
     */
    @Column(name = "transcription_text", columnDefinition = "TEXT")
    private String transcriptionText;
    
    /**
     * Краткий текст расшифровки.
     */
    @Column(name = "brief_text")
    private String briefText;
    
    /**
     * Определенный язык аудио.
     */
    @Column(name = "detected_language", length = 10)
    private String detectedLanguage;
    
    /**
     * Уверенность в расшифровке (0.0 - 1.0).
     */
    @Column(name = "confidence_score")
    private Double confidenceScore;
    
    /**
     * Статус расшифровки.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private TranscriptionStatus status = TranscriptionStatus.PENDING;
    
    /**
     * Сообщение об ошибке, если расшифровка не удалась.
     */
    @Column(name = "error_message")
    private String errorMessage;
    
    /**
     * Количество попыток расшифровки.
     */
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;
    
    /**
     * Время начала расшифровки.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    /**
     * Время завершения расшифровки.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * Дата и время создания записи.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * Дата и время последнего обновления.
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
    
    /**
     * Статусы расшифровки.
     */
    public enum TranscriptionStatus {
        /**
         * Ожидает обработки.
         */
        PENDING,
        
        /**
         * В процессе расшифровки.
         */
        PROCESSING,
        
        /**
         * Расшифровка завершена успешно.
         */
        COMPLETED,
        
        /**
         * Расшифровка не удалась.
         */
        FAILED
    }
}