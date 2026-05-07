CREATE TABLE IF NOT EXISTS recordings (
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(status);
CREATE INDEX IF NOT EXISTS idx_recordings_device_info ON recordings(device_info);
CREATE INDEX IF NOT EXISTS idx_recordings_created_at ON recordings(created_at);
CREATE INDEX IF NOT EXISTS idx_recordings_filename ON recordings(filename);

CREATE TABLE IF NOT EXISTS summaries (
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_summaries_recording FOREIGN KEY (recording_id) 
        REFERENCES recordings(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_summaries_status ON summaries(status);
CREATE INDEX IF NOT EXISTS idx_summaries_recording_id ON summaries(recording_id);
CREATE INDEX IF NOT EXISTS idx_summaries_created_at ON summaries(created_at);
