-- Разрешаем NULL для user_id чтобы устройства могли загружать записи без привязки к пользователю
ALTER TABLE recordings ALTER COLUMN user_id DROP NOT NULL;

-- Добавляем колонку device_login для хранения логина устройства (из DeviceAuthService)
ALTER TABLE recordings ADD COLUMN IF NOT EXISTS device_login VARCHAR(100);

-- Индекс для device_login
CREATE INDEX IF NOT EXISTS idx_recordings_device_login ON recordings(device_login);

-- Комментарии
COMMENT ON COLUMN recordings.user_id IS 'ID пользователя Telegram (NULL для устройств)';
COMMENT ON COLUMN recordings.device_login IS 'Логин устройства (для ESP32-C6 и других устройств)';
