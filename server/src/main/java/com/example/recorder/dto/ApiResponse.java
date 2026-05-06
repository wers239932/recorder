package com.example.recorder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Универсальный DTO для ответов API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    
    private Boolean success;
    private String message;
    private T data;
    private Map<String, String> meta;
    private ApiError error;
    
    /**
     * Успешный ответ с данными.
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .data(data)
            .build();
    }
    
    /**
     * Успешный ответ с сообщением.
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .message(message)
            .data(data)
            .build();
    }
    
    /**
     * Успешный ответ с сообщением и мета-данными.
     */
    public static <T> ApiResponse<T> success(String message, T data, Map<String, String> meta) {
        return ApiResponse.<T>builder()
            .success(true)
            .message(message)
            .data(data)
            .meta(meta)
            .build();
    }
    
    /**
     * Ответ с ошибкой.
     */
    public static <T> ApiResponse<T> error(String message, String code, int status) {
        return ApiResponse.<T>builder()
            .success(false)
            .error(ApiError.builder()
                .message(message)
                .code(code)
                .status(status)
                .timestamp(LocalDateTime.now())
                .build())
            .build();
    }
    
    /**
     * DTO ошибки API.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApiError {
        private String message;
        private String code;
        private int status;
        
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }
}
