>>>>package com.example.recorder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Сущность аудио записи в базе данных.
 * Represents a loaded WAV file with metadata.
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
     * Unique record identifier (UUID).
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    /**
     * ID of the user who owns the record.
     */
    @Column(name = "user_id", length = 36)
    private String userId;

    /**
     * File name on disk.
     */
    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    /**
     * Original file name (from device).
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * File size in bytes.
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /**
     * MIME type of content (audio/wav).
     */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /**
     * Device information (ESP32-C6-Recorder).
     */
    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    /**
     * IP address of the device that uploaded the file.
     */
    @Column(name = "device_ip", length = 45)
    private String deviceIp;
    /**
     * Duration of audio in seconds (filled after analysis).
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * Sample rate (e.g., 16000 Hz).
     */
    @Column(name = "sample_rate")
    private Integer sampleRate;

    /**
     * Number of channels (1 = mono, 2 = stereo).
     */
    @Column(name = "channels")
    private Integer channels;
    /**
     * Processing status of the record.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private RecordingStatus status = RecordingStatus.UPLOADED;
    /**
     * Date and time the record was created.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Date and time of last update.
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    /**
     * Relationship with the user.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserEntity user;
    /**
     * One-to-one relationship with summary.
     */
    @OneToOne(mappedBy = "recording", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private SummaryEntity summary;

    /**
     * One-to-one relationship with text transcription.
     */
    @OneToOne(mappedBy = "recording", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private TranscriptionEntity transcription;
    /**
     * Processing statuses of the record.
     */
    public enum RecordingStatus {
        /**
         * File uploaded, awaiting processing.
         */
        UPLOADED,
        /**
         * File being analyzed (extracting metadata).
         */
        ANALYZING,

        /**
         * Sent for summarization.
         */
        SUMMARIZING,

        /**
         * Summarization completed successfully.
         */
        SUMMARIZED,

        /**
         * Error during summarization.
         */
        SUMMARY_FAILED,
        /**
         * Record ready for use.
         */
        READY,
        /**
         * General processing error.
         */
        FAILED
    }
}
