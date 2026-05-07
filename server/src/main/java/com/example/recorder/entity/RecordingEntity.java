>>>>package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Сущность аудио записи в базе данных.
 * Представляет загруженный WAV-файл с метаданными.
 */
@Entity
@Table(name = "recordings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordingEntity {
    
    /**
     * Уникальный идентификатор записи (UUID).
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    /**
     * ID пользователя, которому принадлежит запись.
     */
    @Column(name = "user_id", length = 36, nullable = unchanged)
    private String userId;

    /**
     * Имя файла на диске.
     */
    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    /**
     * Оригинальное имя файла (от устройства).
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * Размер файла в байтах.
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /**
     * MIME тип контента (audio/wav).
     */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /**
     * Информация об устройстве (ESP32-C6-Recorder).
     */
    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    /**
     * IP адрес устройства, загрузившего файл.
     */
    @Column(name = "device_ip", length = 45)
    private String deviceIp;
    /**
     * Длительность аудио в секундах (заполняется после анализа).
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * Частота дискретизации (например, 16000 Hz).
     */
    @Column(name = "sample_rate")
    private Integer sampleRate;

    /**
     * Количество каналов (1 = mono, 2 = stereo).
     */
    @Column(name = "channels")
    private Integer channels;
    /**
     * Статус обработки записи.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private RecordingStatus status = RecordingStatus.UPLOADED;
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
    /**
     * Связь с пользователем.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserEntity user;
    /**
     * Связь один-к-одному с суммаризацией.
     */
    @OneToOne(mappedBy = "recording", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private SummaryEntity summary;

    /**
     * Связь один-к-одному с текстовой расшифровкой.
     */
    @OneToOne(mappedBy = "recording", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private TranscriptionEntity transcription;
    /**
     * Статусы обработки записи.
     */
    public enum RecordingStatus {
        /**
         * Файл загружен, ожидает обработки.
         */
        UPLOADED,
        /**
         * Файл анализируется (извлечение метаданных).
         */
        ANALYZING,

        /**
         * Отправлено на суммаризацию.
         */
        SUMMARIZING,

        /**
         * Суммаризация завершена успешно.
         */
        SUMMARIZED,

        /**
         * Ошибка при суммаризации.
         */
        SUMMARY_FAILED,
        /**
         * Запись готова к использованию.
         */
        READY,
        /**
         * Общая ошибка обработки.
         */
        FAILED
    }
}
