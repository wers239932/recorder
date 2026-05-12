# 🎙️ Recorder — Полная инструкция по запуску

## Архитектура проекта

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  ESP32-C6       │────▶│  Recorder Server │────▶│  ASR Service    │
│  (запись)       │     │  (порт 8080)     │     │  (порт 8000)    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                │
                                │                ┌─────────────────┐
                                └───────────────▶│  Summarizer     │
                                                 │  (порт 8081)    │
                                                 └─────────────────┘
                                │
                                │
                         ┌──────────────────┐
                         │  Telegram Bot    │
                         │  (клиент)        │
                         └──────────────────┘
```

---

## 🚀 Быстрый старт (все сервисы)

### Шаг 1: ASR-сервис (расшифровка аудио)

```bash
cd "asr copy"
./start_asr.sh 8000
```

**Проверка:**
```bash
curl http://localhost:8000/health
# {"status": "ok"}
```

### Шаг 2: Recorder Server

```bash
cd server
./gradlew bootRun
```

**Проверка:**
```bash
curl http://localhost:8080/api/v1/health
# {"success":true,"data":{"service":"recorder-server","status":"UP"}}
```

### Шаг 3: Telegram Bot

```bash
cd telegram_bot

# Создание .env файла
cat > .env << EOF
BOT_TOKEN=your_bot_token_here
SERVER_URL=http://localhost:8080
EOF

# Запуск
./start_bot.sh
```

---

## 📋 Компоненты

### 1. ASR Service ✅

**Назначение:** Расшифровка аудио в текст (speech-to-text)

**Порт:** 8000

**Зависимости:**
- Python 3.10+
- faster-whisper
- HF_TOKEN (опционально, для загрузки моделей)

**Запуск:**
```bash
cd "asr copy"
./start_asr.sh 8000
```

**Тестирование:**
```bash
curl -X POST http://localhost:8000/transcribe \
  -F "file=@test.wav"
```

---

### 2. Recorder Server ✅

**Назначение:** Хранение записей, API для бота и устройства

**Порт:** 8080

**Зависимости:**
- Java 17+
- Gradle 8.x

**Запуск:**
```bash
cd server
./gradlew bootRun
```

**Тестирование:**
```bash
cd server
./test_transcription.sh
```

---

### 3. Telegram Bot ✅

**Назначение:** Интерфейс для пользователей

**Зависимости:**
- Python 3.10+
- python-telegram-bot
- BOT_TOKEN от @BotFather

**Запуск:**
```bash
cd telegram_bot
./start_bot.sh
```

**Команды:**
- `/start` — приветствие
- `/register` — регистрация
- `/login` — вход
- `/recordings` — список записей
- `/devices` — устройства

**Кнопки:**
- 📄 Текстовая расшифровка
- 📋 Краткое содержание
- 📥 Скачать
- 📝 Переименовать
- ❌ Удалить

---

### 4. Summarizer ⏸️

**Назначение:** Суммаризация текста (краткая выжимка)

**Порт:** 8081 (требуется запуск)

**Зависимости:**
- Python 3.10+
- OPENAI_API_KEY

**Запуск:**
```bash
cd "summarizer copy"
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

cat > .env << EOF
OPENAI_API_KEY=your_key_here
OPENAI_BASE_URL=https://api.vsellm.ru/v1
EOF

uvicorn main:app --host 0.0.0.0 --port 8081
```

---

## 🧪 Тестирование

### Полное тестирование расшифровки

```bash
# 1. Запустить все сервисы (см. выше)

# 2. Запустить тестовый скрипт
cd server
./test_transcription.sh

# 3. Проверить результат в Telegram
# /recordings → выбрать запись → 📄 Текстовая расшифровка
```

### Ручное тестирование API

```bash
# Регистрация
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

# Вход
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"login": "test", "passwordHash": "pwd"}'

# Загрузка файла
curl -X POST http://localhost:8080/api/v1/bot/recordings/upload \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@test.wav"

# Расшифровка
curl -X POST http://localhost:8080/api/v1/bot/recordings/ID/transcribe \
  -H "Authorization: Bearer TOKEN"

# Результат
curl http://localhost:8080/api/v1/bot/recordings/ID/transcription \
  -H "Authorization: Bearer TOKEN"
```

---

## 🔧 Решение проблем

### ASR не загружает модель

**Ошибка:** `Connection refused` или таймаут

**Решение:**
```bash
# Проверить зеркало
curl -I https://hf-mirror.com

# Если не работает, попробовать без VPN
export HF_ENDPOINT=https://huggingface.co
```

### Бот не подключается к серверу

**Ошибка:** `Connection refused`

**Проверка:**
```bash
# Сервер запущен?
curl http://localhost:8080/api/v1/health

# .env файл существует?
cat telegram_bot/.env
```

### Расшифровка не работает

**Ошибка:** `Сервис расшифровки временно недоступен`

**Проверка:**
```bash
# ASR запущен?
curl http://localhost:8000/health

# Порт 8000 слушает?
lsof -i :8000
```

---

## 📁 Структура проекта

```
recorder/
├── asr copy/              # ASR-сервис (faster-whisper)
│   ├── main.py
│   ├── start_asr.sh       # Скрипт запуска
│   └── ...
├── server/                # Spring Boot сервер
│   ├── src/
│   ├── test_transcription.sh  # Тест расшифровки
│   └── ...
├── telegram_bot/          # Telegram бот
│   ├── bot.py
│   ├── start_bot.sh       # Скрипт запуска
│   └── ...
├── summarizer copy/       # Суммаризатор (требует API ключ)
│   ├── main.py
│   └── ...
├── test.wav               # Тестовый файл
├── QUICKSTART.md          # Краткий гайд
└── README.md              # Этот файл
```

---

## 📊 Статус компонентов

| Компонент | Статус | Порт | Примечание |
|-----------|--------|------|------------|
| ASR Service | ✅ Работает | 8000 | Зеркало hf-mirror.com |
| Recorder Server | ✅ Работает | 8080 | Spring Boot |
| Telegram Bot | ✅ Готов | - | Кнопки интегрированы |
| Summarizer | ⏸️ Ждёт ключ | 8081 | Требуется OPENAI_API_KEY |

---

## 🎯 Следующие шаги

1. ✅ Расшифровка работает
2. ⏸️ Суммаризация — нужен OPENAI_API_KEY
3. ⏸️ Прошивка ESP32-C6 — см. `src/`

---

## 📚 Документация

- `QUICKSTART.md` — быстрый старт
- `server/TRANSCRIPTION_TESTING.md` — тестирование расшифровки
- `server/ASR_INTEGRATION.md` — интеграция ASR
- `telegram_bot/README.md` — Telegram бот

---

## 💡 Советы

- Первый запуск ASR может занять 5-10 минут (загрузка модели ~2GB)
- Последующие запуски мгновенные (модель кэшируется)
- Для тестирования без VPN используйте зеркало `hf-mirror.com`
- Сессия в боте истекает через 24 часа
