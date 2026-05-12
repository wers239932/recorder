# Тестирование расшифровки аудио

## Обзор

Этот документ описывает процесс тестирования интеграции сервера Recorder с ASR-сервисом (speech-to-text).

## Быстрый старт

### 1. Запуск сервера

```bash
cd server
./gradlew bootRun
```

Сервер запустится на `http://localhost:8080`.

### 2. Тестирование с test.wav

```bash
# Из директории server (требуется curl и jq)
./test_transcription.sh

# Или с полным путём к файлу
TEST_WAV_FILE=/path/to/your/audio.wav ./test_transcription.sh

# Без VPN (если корпоративный прокси блокирует HuggingFace)
# Скрипт работает с сервером, ASR-сервис опционален
./test_transcription.sh
```

**Что делает скрипт:**
1. Регистрирует тестового пользователя
2. Входит и получает токен
3. Загружает test.wav
4. Запускает расшифровку
5. Ждёт результат (макс. 30 секунд)
6. Показывает текст расшифровки или ошибку

```bash
# 1. Регистрация пользователя
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "telegramId": 123456789,
    "username": "test_user",
    "login": "test_user",
    "passwordHash": "password123",
    "firstName": "Test",
    "lastName": "User"
  }'

# Сохраните токен из ответа

# 2. Загрузка аудио
curl -X POST http://localhost:8080/api/v1/bot/recordings/upload \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@test.wav" \
  -F "deviceInfo=test-device"

# Сохраните recording_id из ответа

# 3. Запуск расшифровки
curl -X POST http://localhost:8080/api/v1/bot/recordings/{recording_id}/transcribe \
  -H "Authorization: Bearer YOUR_TOKEN"

# 4. Получение результата (подождать 3-5 секунд)
curl http://localhost:8080/api/v1/bot/recordings/{recording_id}/transcription \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

## Архитектура тестирования

```
┌──────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  test.wav    │────▶│  Recorder Server │────▶│  Mock ASR       │
│  (audio)     │     │  (Spring Boot)   │     │  (симуляция)    │
└──────────────┘     └──────────────────┘     └─────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  H2 Database     │
                     │  (transcriptions)│
                     └──────────────────┘
```

**Режимы работы:**

1. **Мок-расшифровка** (по умолчанию) — сервер симулирует расшифровку, возвращая тестовый текст
2. **Реальная расшифровка** — сервер вызывает внешний ASR-сервис (faster-whisper)

---

## Интеграционные тесты JUnit

### Запуск всех тестов

```bash
cd server
./gradlew test
```

### Запуск конкретного теста

```bash
./gradlew test --tests "TranscriptionApiIntegrationTest"
```

### Тесты покрывают:

- ✅ Полный цикл: загрузка → расшифровка → результат
- ✅ Обработку ошибок (несуществующая запись, нет авторизации)
- ✅ Статусы расшифровки (PENDING, PROCESSING, COMPLETED, FAILED)
- ✅ Проверку прав доступа пользователя

---

## Настройка реальной расшифровки

### 1. Запуск ASR-сервиса

```bash
cd "asr copy"

# Создание виртуального окружения (если ещё не создано)
python3 -m venv venv
source venv/bin/activate

# Установка зависимостей
pip install fastapi uvicorn python-multipart faster-whisper

# Запуск (требуется HuggingFace токен)
export HF_TOKEN=hf_xxxxxxxxxxxxx
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. Настройка сервера

В `application.yml`:

```yaml
transcription:
  client:
    base-url: http://localhost:8000
    timeout: 60s
    auto-transcribe: false
```

### 3. Проверка работы ASR

```bash
curl -X POST http://localhost:8000/transcribe \
  -F "file=@test.wav"
```

Ожидаемый ответ:
```json
{
  "text": "Расшифрованный текст..."
}
```

---

## API Endpoints

### POST /api/v1/bot/recordings/{id}/transcribe

Запуск расшифровки для записи.

**Параметры:**
- `id` (path) — ID записи
- `language` (query, optional) — код языка (ru, en)

**Ответ (200 OK):**
```json
{
  "success": true,
  "data": {
    "recordingId": "...",
    "status": "PROCESSING",
    "message": "Расшифровка запущена"
  }
}
```

### GET /api/v1/bot/recordings/{id}/transcription

Получение результата расшифровки.

**Ответ (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "recordingId": "...",
    "transcriptionText": "Полный текст расшифровки",
    "briefText": "Краткая версия...",
    "detectedLanguage": "ru",
    "confidenceScore": 0.95,
    "status": "COMPLETED",
    "startedAt": "2026-05-11T23:00:00",
    "completedAt": "2026-05-11T23:00:05"
  }
}
```

**Ответ (404 Not Found):**
```json
{
  "success": false,
  "message": "Расшифровка не найдена"
}
```

### Статусы расшифровки

| Статус | Описание |
|--------|----------|
| `PENDING` | Ожидает обработки |
| `PROCESSING` | В процессе расшифровки |
| `COMPLETED` | Расшифровка завершена успешно |
| `FAILED` | Ошибка расшифровки |

---

## Скрипт test_transcription.sh

### Возможности

- ✅ Автоматическая регистрация пользователя
- ✅ Загрузка WAV-файла
- ✅ Запуск расшифровки
- ✅ Ожидание результата с retry
- ✅ Вывод текста расшифровки
- ✅ Проверка зависимостей (curl, jq)

### Параметры

```bash
./test_transcription.sh [SERVER_URL]

# Примеры:
./test_transcription.sh                          # http://localhost:8080
./test_transcription.sh http://192.168.1.100:8080
TEST_WAV_FILE=./my-audio.wav ./test_transcription.sh
```

### Зависимости

- `curl` — HTTP-клиент
- `jq` — парсинг JSON

Установка на macOS:
```bash
brew install curl jq
```

---

## Troubleshooting

### Сервер не запускается

```bash
# Проверьте порт 8080
lsof -i :8080

# Очистите порт или измените в application.yml
server:
  port: 8081
```

### Ошибка "Connection refused" к ASR

```bash
# Проверьте, запущен ли ASR-сервис
curl http://localhost:8000/health

# Проверьте логи ASR
cd "asr copy" && source venv/bin/activate && uvicorn main:app --port 8000
```

### Расшифровка не завершается

Проверьте логи сервера:
```
Вызов ASR-сервиса для расшифровки: {id}
ASR-сервис вернул расшифровку для записи: {id}
Сохранен результат расшифровки для записи: {id}
```

Если видите только первую строку — ASR-сервис недоступен.

### Ошибка "Файл не найден"

```bash
# Убедитесь, что test.wav существует
ls -la test.wav

# Или укажите полный путь
TEST_WAV_FILE=/absolute/path/to/test.wav ./test_transcription.sh
```

---

## Следующие шаги

1. ✅ Тестирование с мок-расшифровкой
2. ⏸️ Настройка реального ASR-сервиса (нужен HF_TOKEN)
3. ⏸️ Интеграция с Telegram-ботом (кнопка "Расшифровка")
4. ⏸️ Суммаризация текста (нужен OPENAI_API_KEY)

---

## Контакты

Вопросы и предложения: см. NESSY.md
