package com.example.recorder.dto;

import com.example.recorder.entity.TranscriptionEntity.TranscriptionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO ответа с информацией о текстовой расшифровке.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TranscriptionResponse {
    
    private String id;
    private String recordingId;
    private String transcriptionText;
    private String briefText;
    private String detectedLanguage;
    private Double confidenceScore;
    private TranscriptionStatus status;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}