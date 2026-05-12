package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Сущность суммаризации аудио записи.
 * Содержит результат обработки аудиофайла сервисом суммаризации.
 */
@Entity
@Table(name = "summaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SummaryEntity {
    
    /**
     * Уникальный идентификатор суммаризации (UUID).
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    /**
     * Связь с записью (один-к-одному).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_id", nullable = false, unique = true)
    private RecordingEntity recording;
    
    /**
     * Текстовая суммаризация аудио.
     * Заполняется из Summarizer API.
     */
    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    /**
     * Краткое содержание (one-line summary).
     * Не используется (Summarizer API не возвращает).
     */
    @Column(name = "brief_summary", length = 500)
    private String briefSummary;

    /**
     * Ключевые слова/теги, извлечённые из аудио.
     * Не используется (Summarizer API не возвращает).
     */
    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    /**
     * Уверенность модели суммаризации (0.0 - 1.0).
     * Не используется (Summarizer API не возвращает).
     */
    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    /**
     * Язык распознанной речи (ISO 639-1).
     * Не используется (Summarizer API не возвращает).
     */
    @Column(name = "detected_language", length = 10)
    private String detectedLanguage;
    
    /**
     * Статус суммаризации.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private SummaryStatus status = SummaryStatus.PENDING;
    
    /**
     * Сообщение об ошибке (если суммаризация не удалась).
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * Количество попыток суммаризации.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;
    
    /**
     * Время начала обработки.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    /**
     * Время завершения обработки.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * Дата создания записи.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * Дата последнего обновления.
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    /**
     * Статусы суммаризации.
     */
    public enum SummaryStatus {
        /**
         * Ожидает обработки.
         */
        PENDING,
        
        /**
         * В процессе суммаризации.
         */
        PROCESSING,
        
        /**
         * Суммаризация завершена успешно.
         */
        COMPLETED,
        
        /**
         * Ошибка суммаризации.
         */
        FAILED
    }
}
