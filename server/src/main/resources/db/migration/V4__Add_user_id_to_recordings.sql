-- Добавляем поле user_id в таблицу recordings (если ещё не существует)
ALTER TABLE recordings 
ADD COLUMN IF NOT EXISTS user_id VARCHAR(36);

ALTER TABLE recordings 
ADD COLUMN IF NOT EXISTS user_recording_name VARCHAR(255);

-- Создаём индекс для user_id для ускорения запросов
CREATE INDEX IF NOT EXISTS idx_recordings_user_id ON recordings(user_id);

-- Добавляем внешний ключ для связи с таблицей users (если ещё не существует)
ALTER TABLE recordings 
ADD CONSTRAINT IF NOT EXISTS fk_recordings_user 
    FOREIGN KEY (user_id) 
    REFERENCES users(id) 
    ON DELETE SET NULL;