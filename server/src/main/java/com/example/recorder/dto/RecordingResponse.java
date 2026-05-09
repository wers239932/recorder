package com.example.recorder.dto;

import com.example.recorder.entity.RecordingEntity.RecordingStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO ответа с информацией о записи.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingResponse {
    
    private String id;
    private String filename;
    private String originalFilename;
    private Long fileSize;
    private String contentType;
    private String deviceInfo;
    private String deviceIp;
    private String deviceLogin;
    private String userId;
    private Integer durationSeconds;
    private Integer sampleRate;
    private Integer channels;
    private RecordingStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    /**
     * Вложенная информация о суммаризации (если есть).
     */
    private SummaryResponse summary;
    
    /**
     * URL для скачивания файла.
     */
    private String downloadUrl;
    
    /**
     * Создание ответа из entity.
     */
    public static RecordingResponse fromEntity(
            com.example.recorder.entity.RecordingEntity entity,
            String baseUrl) {
        
        var summary = entity.getSummary() != null
            ? SummaryResponse.fromEntity(entity.getSummary())
            : null;
        
        return RecordingResponse.builder()
            .id(entity.getId())
            .filename(entity.getFilename())
            .originalFilename(entity.getOriginalFilename())
            .fileSize(entity.getFileSize())
            .contentType(entity.getContentType())
            .deviceInfo(entity.getDeviceInfo())
            .deviceIp(entity.getDeviceIp())
            .deviceLogin(entity.getDeviceLogin())
            .userId(entity.getUserId())
            .durationSeconds(entity.getDurationSeconds())
            .sampleRate(entity.getSampleRate())
            .channels(entity.getChannels())
            .status(entity.getStatus())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .summary(summary)
            .downloadUrl(baseUrl + "/api/v1/recordings/" + entity.getId() + "/download")
            .build();
    }
}
