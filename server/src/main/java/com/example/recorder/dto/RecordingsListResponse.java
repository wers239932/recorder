package com.example.recorder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

/**
 * DTO ответа со списком записей.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingsListResponse {
    
    private List<RecordingResponse> recordings;
    private Integer total;
    private Integer page;
    private Integer size;
    private Integer totalPages;
    private Boolean hasNext;
    private Boolean hasPrevious;
    
    /**
     * Создание ответа для страницы записей.
     */
    public static RecordingsListResponse fromPage(
            List<RecordingResponse> recordings,
            int total,
            int page,
            int size,
            int totalPages) {
        
        return RecordingsListResponse.builder()
            .recordings(recordings)
            .total(total)
            .page(page)
            .size(size)
            .totalPages(totalPages)
            .hasNext(page < totalPages - 1)
            .hasPrevious(page > 0)
            .build();
    }
}
