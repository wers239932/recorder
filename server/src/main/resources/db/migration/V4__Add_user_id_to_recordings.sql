-- Добавляем поле user_id в таблицу recordings
ALTER TABLE recordings 
ADD COLUMN user_id VARCHAR(36),
ADD COLUMN user_recording_name VARCHAR(255);

-- Создаём индекс для user_id для ускорения запросов
CREATE INDEX IF NOT EXISTS idx_recordings_user_id ON recordings(user_id);

-- Добавляем внешний ключ для связи с таблицей users
ALTER TABLE recordings 
ADD CONSTRAINT fk_recordings_user 
    FOREIGN KEY (user_id) 
    REFERENCES users(id) 
    ON DELETE SET NULL;