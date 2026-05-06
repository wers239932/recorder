package com.example.recorder.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Временный MultipartFile для обработки raw данных.
 * Используется для конвертации byte[] в формат, совместимый с RecordingService.
 */
public class TempMultipartFile implements MultipartFile {
    
    private final byte[] fileContent;
    private final String filename;
    private final String contentType;
    
    public TempMultipartFile(byte[] fileContent, String filename, String contentType) {
        this.fileContent = fileContent;
        this.filename = filename;
        this.contentType = contentType;
    }
    
    @Override
    public String getName() {
        return "file";
    }
    
    @Override
    public String getOriginalFilename() {
        return filename;
    }
    
    @Override
    public String getContentType() {
        return contentType;
    }
    
    @Override
    public boolean isEmpty() {
        return fileContent == null || fileContent.length == 0;
    }
    
    @Override
    public long getSize() {
        return fileContent != null ? fileContent.length : 0L;
    }
    
    @Override
    public byte[] getBytes() {
        return fileContent != null ? fileContent : new byte[0];
    }
    
    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(fileContent != null ? fileContent : new byte[0]);
    }
    
    @Override
    public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
        if (fileContent != null) {
            java.nio.file.Files.write(dest.toPath(), fileContent);
        }
    }
}
