# Быстрый старт: Расшифровка аудио

## Компоненты

```
┌──────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  test.wav    │────▶│  Recorder Server │────▶│  ASR Service    │
│  (клиент)    │     │  (порт 8080)     │     │  (порт 8000)    │
└──────────────┘     └──────────────────┘     └─────────────────┘
```

---

## Шаг 1: Запуск ASR-сервиса (порт 8000)

```bash
cd "asr copy"

# Запуск через скрипт (рекомендуется)
./start_asr.sh 8000

# Или вручную:
python3 -m venv venv
source venv/bin/activate
pip install fastapi uvicorn python-multipart faster-whisper
export HF_ENDPOINT=https://hf-mirror.com
uvicorn main:app --host 0.0.0.0 --port 8000
```

**Проверка:**
```bash
curl http://localhost:8000/health
# {"status": "ok"}
```

**Важно:** Первый запуск может занять 5-10 минут (загрузка модели ~2GB через зеркало).

---

## Шаг 2: Запуск Recorder Server (порт 8080)

```bash
cd server
./gradlew bootRun
```

**Проверка:**
```bash
curl http://localhost:8080/api/v1/health
# {"success":true,"data":{"service":"recorder-server","status":"UP"}}
```

---

## Шаг 3: Тестирование расшифровки

### Вариант A: Автоматический тест (рекомендуется)

```bash
cd server
./test_transcription.sh
```

Скрипт автоматически:
1. Зарегистрирует пользователя
2. Загрузит test.wav
3. Запустит расшифровку
4. Покажет результат

### Вариант B: Вручную через curl

```bash
# 1. Регистрация
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "telegramId": 123456,
    "username": "test",
    "login": "test",
    "passwordHash": "pwd",
    "firstName": "Test",
    "lastName": "User"
  }'

# 2. Вход (сохраните токен)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"login": "test", "passwordHash": "pwd"}'

# 3. Загрузка файла (замените TOKEN)
curl -X POST http://localhost:8080/api/v1/bot/recordings/upload \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@test.wav"

# 4. Запуск расшифровки (замените RECORDING_ID)
curl -X POST http://localhost:8080/api/v1/bot/recordings/RECORDING_ID/transcribe \
  -H "Authorization: Bearer TOKEN"

# 5. Получение результата (подождать 5-30 секунд)
curl http://localhost:8080/api/v1/bot/recordings/RECORDING_ID/transcription \
  -H "Authorization: Bearer TOKEN"
```

---

## Решение проблем

### ASR-сервис не запускается

**Ошибка:** `Connection refused` или таймаут при загрузке модели

**Решение:**
```bash
# Проверьте, что зеркало доступно
curl -I https://hf-mirror.com

# Если не работает, попробуйте без VPN
# Или используйте другой прокси
export HF_ENDPOINT=https://huggingface.co
```

### Recorder Server не видит ASR

**Ошибка:** `Connection refused: localhost/127.0.0.1:8000`

**Проверка:**
```bash
# ASR-сервис запущен?
curl http://localhost:8000/health

# Порт 8000 слушает?
lsof -i :8000
```

### test.wav не найден

```bash
# Проверьте путь к файлу
ls -la /Users/ki.a.kuznetsov/IdeaProjects/recorder/test.wav

# Или укажите полный путь
TEST_WAV_FILE=/full/path/to/test.wav ./test_transcription.sh
```

---

## Архитектура

| Компонент | Порт | Описание |
|-----------|------|----------|
| ASR Service | 8000 | Расшифровка аудио (faster-whisper) |
| Recorder Server | 8080 | Spring Boot, хранение записей |
| Telegram Bot | - | Клиент для Recorder Server |

---

## Следующие шаги

1. ✅ Расшифровка работает
2. ⏸️ Суммаризация — нужен OPENAI_API_KEY
3. ⏸️ Telegram-бот — добавить кнопки

---

## Контакты

Вопросы: см. `INESSY.md`
