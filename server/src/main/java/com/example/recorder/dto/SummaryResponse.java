package com.example.recorder.dto;

import com.example.recorder.entity.SummaryEntity.SummaryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO ответа с информацией о суммаризации.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SummaryResponse {
    
    private String id;
    private String recordingId;
    private String summaryText;
    private String briefSummary;
    private List<String> keywords;
    private Double confidenceScore;
    private String detectedLanguage;
    private SummaryStatus status;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    
    /**
     * Создание ответа из entity.
     */
    public static SummaryResponse fromEntity(com.example.recorder.entity.SummaryEntity entity) {
        return SummaryResponse.builder()
            .id(entity.getId())
            .recordingId(entity.getRecording().getId())
            .summaryText(entity.getSummaryText())
            .briefSummary(entity.getBriefSummary())
            .keywords(entity.getKeywords() != null 
                ? java.util.Arrays.asList(entity.getKeywords().split(",")) 
                : null)
            .confidenceScore(entity.getConfidenceScore() != null
                ? entity.getConfidenceScore().doubleValue()
                : null)
            .detectedLanguage(entity.getDetectedLanguage())
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .retryCount(entity.getRetryCount())
            .startedAt(entity.getStartedAt())
            .completedAt(entity.getCompletedAt())
            .createdAt(entity.getCreatedAt())
            .build();
    }
}
