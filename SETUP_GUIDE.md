# Подробное руководство по запуску интеграции ESP32-C6 + Сервер + Telegram-бот

## Требования

### Для сервера (Spring Boot)
- ✅ Java 17 или выше
- ✅ Maven или Gradle
- ✅ PostgreSQL (опционально, по умолчанию H2)

### Для ESP32-C6
- ✅ PlatformIO (расширение VS Code или CLI)
- ✅ ESP32-C6 DevKitC-1 или Waveshare ESP32-C6-LCD-1.47
- ✅ USB-кабель для подключения

### Для Telegram-бота
- ✅ Python 3.8+
- ✅ `python-telegram-bot` v20+
- ✅ Токен бота от @BotFather

---

## Шаг 1: Запуск сервера Spring Boot

### 1.1 Проверка Java

```bash
java -version
# Должно быть: java version "17" или выше
```

### 1.2 Перейдите в директорию сервера

```bash
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder/server
```

### 1.3 Запуск сервера

```bash
# Сборка и запуск
./gradlew bootRun
```

**Ожидаемый результат:**
```
Started RecorderServerApplication in X.XXX seconds
Tomcat started on port(s): 8080 (http)
```

### 1.4 Проверка работоспособности

Откройте терминал и выполните:

```bash
curl http://localhost:8080/api/v1/health
```

**Ожидаемый ответ:**
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "timestamp": "2026-05-09T..."
  }
}
```

### 1.5 (Опционально) Настройка базы данных

По умолчанию используется H2 (в памяти). Для PostgreSQL:

1. Отредактируйте `server/src/main/resources/application.properties`:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/recorder
   spring.datasource.username=recorder
   spring.datasource.password=your_password
   spring.jpa.hibernate.ddl-auto=validate
   ```

2. Создайте базу данных:
   ```sql
   CREATE DATABASE recorder;
   CREATE USER recorder WITH PASSWORD 'your_password';
   GRANT ALL PRIVILEGES ON DATABASE recorder TO recorder;
   ```

---

## Шаг 2: Настройка и загрузка прошивки ESP32-C6

### 2.1 Установка PlatformIO

```bash
# Через pip
pip install platformio

# Или через VS Code: установите расширение "PlatformIO IDE"
```

### 2.2 Проверка конфигурационного файла

**Важно:** Прошивка читает конфиг из **SD-карты**, а не SPIFFS!

**Модель 1:1** — логин и пароль устройства должны совпадать с логином и паролем пользователя Telegram.

Файл `data/sdcard/auth.txt` должен содержать:

```json
{
  "login": "testuser",
  "password": "password123",
  "server_url": "http://192.168.1.12:8080/api/v1/device/auth/login",
  "upload_url": "http://192.168.1.12:8080/api/v1/data/upload",
  "command_url": "http://192.168.1.12:8080/api/v1/device/command"
}
```

**Важно:** 
- Замените `192.168.1.12` на IP-адрес вашего сервера
- Используйте те же `login` и `password`, которые будете вводить при регистрации в Telegram-боте

### 2.3 Копирование файла на SD-карту

1. Извлеките SD-карту из ESP32-C6
2. Отформатируйте в FAT32 (если новая)
3. Скопируйте файл `data/sdcard/auth.txt` в корень SD-карты
4. Вставьте SD-карту обратно в плату

**Или через терминал (если плата подключена):**

```bash
# macOS
cp /Users/ki.a.kuznetsov/IdeaProjects/recorder/data/sdcard/auth.txt /Volumes/NO_NAME/auth.txt

# Linux
cp /Users/ki.a.kuznetsov/IdeaProjects/recorder/data/sdcard/auth.txt /media/$USER/NO_NAME/auth.txt

# Windows
# Скопируйте файл вручную через Проводник
```

### 2.4 Подключение платы

1. Подключите ESP32-C6 к компьютеру через USB
2. Определите порт:
   ```bash
   # macOS
   ls /dev/cu.usbserial*
   
   # Linux
   ls /dev/ttyUSB*
   
   # Windows
   # Диспетчер устройств → Порты (COM и LPT)
   ```

### 2.5 Настройка platformio.ini

Откройте `platformio.ini` и убедитесь, что настройки верны:

```ini
[env:esp32-c6-devkitc-1]
platform = espressif32
board = esp32-c6-devkitc-1
framework = espidf
monitor_speed = 115200
```

**Примечание:** `uploadfs` не требуется — конфиг читается напрямую с SD-карты.

### 2.6 Загрузка прошивки

```bash
pio run --target upload
```

**Ожидаемый результат:**
```
Writing at 0x00010000... (100 %)
Wrote ... bytes at 0x00010000 in ... seconds
```

### 2.7 Мониторинг логов

```bash
pio device monitor
```

**Ожидаемые логи:**
```
I (1234) AUTH: Auth config loaded: login=0006
I (1234) WIFI: Connecting to WiFi...
I (2345) WIFI: Connected to AP, IP=192.168.1.XXX
I (3456) AUTH: Requesting auth token from http://192.168.1.12:8080/api/v1/device/auth/login
I (4567) AUTH: Device authenticated successfully
```

**Для выхода из мониторинга:** нажмите `Ctrl+]`

---

## Шаг 3: Настройка и запуск Telegram-бота

### 3.1 Создание бота в Telegram

1. Откройте @BotFather в Telegram
2. Отправьте `/newbot`
3. Введите имя бота (например, `Recorder Bot`)
4. Введите username бота (например, `recorder_dev_bot`)
5. Скопируйте токен (выглядит как `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)

### 3.2 Установка зависимостей

```bash
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder/telegram_bot

# Создание виртуального окружения (рекомендуется)
python3 -m venv venv
source venv/bin/activate  # macOS/Linux
# или
venv\Scripts\activate  # Windows

# Установка зависимостей
pip install -r requirements.txt
```

### 3.3 Настройка бота

Откройте `bot.py` и найдите строку с токеном:

```python
BOT_TOKEN = "YOUR_BOT_TOKEN_HERE"
```

Замените на ваш токен от @BotFather.

Или создайте файл `.env`:

```bash
# telegram_bot/.env
BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
SERVER_URL=http://localhost:8080
```

### 3.4 Запуск бота

```bash
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder/telegram_bot
python bot.py
```

**Ожидаемый результат:**
```
2026-05-09 12:00:00 - telegram.ext.Application - INFO - Application started
2026-05-09 12:00:00 - __main__ - INFO - Bot started polling...
```

---

## Шаг 4: Тестирование интеграции

### 4.1 Регистрация пользователя в боте

1. Откройте вашего бота в Telegram
2. Отправьте `/start`
3. Отправьте `/register`
4. Введите логин (например, `testuser`)
5. Введите пароль (например, `password123`)

**Ожидаемый ответ:**
```
✅ Регистрация успешна!

Логин: testuser
Теперь вы можете войти командой /login
```

### 4.2 Вход в бота

1. Отправьте `/login`
2. Введите логин: `testuser`
3. Введите пароль: `password123`

**Ожидаемый ответ:**
```
✅ Вход выполнен!

Теперь вы можете использовать:
/recordings - список записей
/devices - список устройств
```

### 4.3 Привязка устройства к пользователю

**В модели 1:1 устройство автоматически привязывается при первой аутентификации!**

1. Убедитесь, что логин и пароль в `auth.txt` совпадают с данными пользователя (`testuser` / `password123`)
2. Дождитесь первой аутентификации устройства (см. логи платы)
3. Устройство автоматически привяжется к пользователю

**Проверка в боте:**
1. Отправьте `/devices`
2. Должно отобразиться: `📱 Ваши устройства (1): testuser`

### 4.4 Запись аудио с платы

1. Нажмите кнопку на плате ESP32-C6 для начала записи
2. Подождите несколько секунд
3. Нажмите кнопку снова для остановки

**Проверка в логах платы:**
```
I (12345) RECORDER: Recording started
I (22345) RECORDER: Recording stopped, saved to /sdcard/recordings/rec_001.wav
I (23456) UPLOADER: Uploading recording to server...
I (24567) UPLOADER: Upload successful, id=uuid-xxx
```

### 4.5 Проверка записей в боте

В боте отправьте: `/recordings`

**Ожидаемый ответ:**
```
📼 Ваши записи (1):

🎵 Запись #1
📅 09.05.2026 12:30
⏱️ 10 сек
📱 Устройство: 0006

[📥 Скачать] [📝 Переименовать] [✂️ Удалить]
```

---

## Шаг 5: Проверка API через curl

### 5.1 Аутентификация устройства

```bash
curl -X POST http://localhost:8080/api/v1/device/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "login": "testuser",
    "password_hash": "эффект123_hash"
  }' | jq .
```

**Ожидаемый ответ:**
```json
{
  "success": true,
  "data": {
    "token": "uuid-token",
    "expires_in": 86400
  }
}
```

### 5.2 Получение токена пользователя

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "login": "testuser",
    "passwordHash": "эффект123"
  }' | jq '.data.token'
```

### 5.3 Список устройств

```bash
curl -X GET http://localhost:8080/api/v1/bot/devices \
  -H "Authorization: Bearer <USER_TOKEN>" | jq .
```

**Ожидаемый ответ:**
```json
{
  "success": true,
  "data": ["testuser"]
}
```

### 5.4 Список записей

```bash
curl -X GET http://localhost:8080/api/v1/bot/recordings \
  -H "Authorization: Bearer <USER_TOKEN>" | jq '.data[] | {id, deviceLogin, filename}'
```

---

## Устранение неполадок

### Сервер не запускается

**Проблема:** `Port 8080 already in use`

**Решение:**
```bash
# Найти процесс на порту 8080
lsof -i :8080

# Убить процесс
kill -9 <PID>

# Или изменить порт в application.properties
server.port=8081
```

### Плата не подключается к WiFi

**Проблема:** `WIFI: Connection failed`

**Решение:**
1. Проверьте SSID и пароль в прошивке
2. Убедитесь, что WiFi 2.4GHz (ESP32 не поддерживает 5GHz)
3. Проверьте логи через `pio device monitor`

### Плата не аутентифицируется

**Проблема:** `HTTP 401 Unauthorized`

**Решение:**
1. Проверьте `server_url` в `auth_config.json`
2. Проверьте, что сервер доступен с устройства:
   ```bash
   # В логах платы должно быть:
   I (1234) AUTH: Device authenticated successfully
   ```
3. Проверьте хеш пароля:
   ```bash
   echo -n "esp32_device_secret_key" | sha256sum
   ```

### Бот не отвечает

**Проблема:** Нет реакции на команды

**Решение:**
1. Проверьте токен бота в `bot.py`
2. Проверьте логи бота на наличие ошибок
3. Убедитесь, что сервер доступен:
   ```bash
   curl http://localhost:8080/api/v1/health
   ```

### Записи не отображаются в боте

**Проблема:** `/recordings` показывает 0 записей

**Решение:**
1. Убедитесь, что устройство привязано: `/devices`
2. Проверьте, что запись загружена на сервер (логи платы)
3. Проверьте `deviceLogin` в записи:
   ```bash
   curl -X GET http://localhost:8080/api/v1/recordings \
     -H "Authorization: Bearer <USER_TOKEN>" | jq '.data[].deviceLogin'
   ```

---

## Быстрые команды

```bash
# Перезапуск сервера
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder/server
./gradlew bootRun

# Перезагрузка прошивки
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder
pio run --target uploadfs --target upload

# Перезапуск бота
cd /Users/ki.a.kuznetsov/IdeaProjects/recorder/telegram_bot
source venv/bin/activate
python bot.py

# Мониторинг логов платы
pio device monitor

# Проверка здоровья сервера
curl http://localhost:8080/api/v1/health | jq .
```

---

## Контакты и поддержка

При возникновении проблем:
1. Проверьте логи сервера: `tail -f /tmp/server.log`
2. Проверьте логи платы: `pio device monitor`
3. Проверьте логи бота: вывод в терминале
