package com.example.recorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * DTO запроса загрузки аудио записи.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadRecordingRequest {
    
    /**
     * Информация об устройстве (опционально).
     */
    @Size(max = 255, message = "deviceInfo must be less than 255 characters")
    private String deviceInfo;
    
    /**
     * IP адрес устройства (заполняется сервером).
     */
    private String deviceIp;
    
    /**
     * Оригинальное имя файла.
     */
    @NotBlank(message = "filename is required")
    private String filename;
    
    /**
     * Размер файла в байтах.
     */
    private Long fileSize;
    
    /**
     * Content type файла.
     */
    private String contentType;
}
