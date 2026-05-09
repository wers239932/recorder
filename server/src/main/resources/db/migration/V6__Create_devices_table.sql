-- Таблица устройств для динамической регистрации ESP32-C6 и других устройств
-- Устройства могут быть привязаны к пользователю (user_id) или работать автономно

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(36) PRIMARY KEY,
    login VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    user_id VARCHAR(36) NULL,
    session_token VARCHAR(255) UNIQUE NULL,
    session_expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL,
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Индексы для быстрого поиска
CREATE INDEX IF NOT EXISTS idx_devices_login ON devices(login);
CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_session_token ON devices(session_token);

-- Комментарии
COMMENT ON TABLE devices IS 'Устройства (ESP32-C6 и другие) для динамической аутентификации';
COMMENT ON COLUMN devices.id IS 'UUID устройства';
COMMENT ON COLUMN devices.login IS 'Логин устройства (например, MAC-адрес)';
COMMENT ON COLUMN devices.password_hash IS 'Хеш пароля устройства (SHA-256)';
COMMENT ON COLUMN devices.user_id IS 'Связь с пользователем Telegram (NULL для автономных устройств)';
COMMENT ON COLUMN devices.session_token IS 'Токен сессии устройства для Bearer-аутентификации';
COMMENT ON COLUMN devices.session_expires_at IS 'Время истечения сессии устройства';
