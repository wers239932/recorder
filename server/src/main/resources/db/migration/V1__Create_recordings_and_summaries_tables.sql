-- V1__Create_recordings_and_summaries_tables.sql
-- Создание таблиц для аудио записей и суммаризаций

-- Таблица аудио записей
CREATE TABLE recordings (
    id VARCHAR(36) PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255),
    file_size BIGINT NOT NULL,
    content_type VARCHAR(100),
    device_info VARCHAR(255),
    device_ip VARCHAR(45),
    duration_seconds INTEGER,
    sample_rate INTEGER,
    channels INTEGER,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Индексы для оптимизации поиска
CREATE INDEX idx_recordings_status ON recordings(status);
CREATE INDEX idx_recordings_device_info ON recordings(device_info);
CREATE INDEX idx_recordings_created_at ON recordings(created_at DESC);
CREATE INDEX idx_recordings_filename ON recordings(filename);

-- Таблица суммаризаций
CREATE TABLE summaries (
    id VARCHAR(36) PRIMARY KEY,
    recording_id VARCHAR(36) NOT NULL UNIQUE,
    summary_text TEXT,
    brief_summary VARCHAR(500),
    keywords TEXT,
    confidence_score DECIMAL(5,4),
    detected_language VARCHAR(10),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_summaries_recording FOREIGN KEY (recording_id) 
        REFERENCES recordings(id) ON DELETE CASCADE
);

-- Индексы для суммаризаций
CREATE INDEX idx_summaries_status ON summaries(status);
CREATE INDEX idx_summaries_recording_id ON summaries(recording_id);
CREATE INDEX idx_summaries_created_at ON summaries(created_at DESC);

-- Комментарий к таблицам
COMMENT ON TABLE recordings IS 'Аудио записи с ESP32-C6 устройства';
COMMENT ON TABLE summaries IS 'Результаты суммаризации аудио записей';
