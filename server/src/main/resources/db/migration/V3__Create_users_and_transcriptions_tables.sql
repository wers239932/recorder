-- Создание таблицы пользователей
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    telegram_id BIGINT NOT NULL UNIQUE,
    username VARCHAR(255) UNIQUE,
    login VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    session_token VARCHAR(255) UNIQUE,
    session_expires_at TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

-- Создание таблицы транскрипций
CREATE TABLE transcriptions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    recording_id VARCHAR(36) NOT NULL UNIQUE,
    transcription_text TEXT,
    brief_text VARCHAR(1000),
    detected_language VARCHAR(10),
    confidence_score DOUBLE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(1000),
    retry_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (recording_id) REFERENCES recordings(id) ON DELETE CASCADE
);

-- Добавление колонки user_id в таблицу recordings
ALTER TABLE recordings ADD COLUMN user_id VARCHAR(36) NOT NULL DEFAULT 'default-user';

-- Создание индексов
CREATE INDEX idx_users_telegram_id ON users(telegram_id);
CREATE INDEX idx_users_login ON users(login);
CREATE INDEX idx_users_session_token ON users(session_token);
CREATE INDEX idx_transcriptions_recording_id ON transcriptions(recording_id);
CREATE INDEX idx_recordings_user_id ON recordings(user_id);

-- Установка значения по умолчанию для существующих записей
UPDATE recordings SET user_id = 'legacy-device' WHERE user_id = 'default-user';

-- Удаление колонки после установки значений (SQLite не поддерживает ALTER TABLE DROP COLUMN,
-- поэтому для H2 это должен работать)
-- Для H2: ALTER TABLE recordings DROP COLUMN user_id;
-- Но оставим колонку так как она теперь нужна