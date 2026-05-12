# Интеграция ASR и Summarizer сервисов

## Обзор

Сервер Recorder теперь интегрирован с двумя внешними сервисами:

1. **ASR (Automatic Speech Recognition)** — расшифровка аудио в текст (speech-to-text)
2. **Summarizer** — суммаризация текста (краткая выжимка)

## Архитектура

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Telegram Bot   │     │  Recorder Server │     │  ASR Service    │
│  / Bot API      │────▶│  (Spring Boot)   │────▶│  (FastAPI)      │
│                 │     │  Transcription   │     │  faster-whisper │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                │
                                │                ┌─────────────────┐
                                └───────────────▶│  Summarizer     │
                                                 │  (FastAPI)      │
                                                 │  OpenAI API     │
                                                 └─────────────────┘
```

## 1. ASR Сервис (speech2text)

### Расположение
`asr copy/` — сервис на базе `faster-whisper` (CPU)

### Быстрый старт

```bash
cd "asr copy"

# Вариант 1: Через скрипт (рекомендуется)
./start_asr.sh 8000

# Вариант 2: Вручную
python3 -m venv venv
source venv/bin/activate

# Установка зависимостей
pip install fastapi uvicorn python-multipart faster-whisper

# Запуск с зеркалом HuggingFace (для обхода прокси)
export HF_ENDPOINT=https://hf-mirror.com
uvicorn main:app --host 0.0.0.0 --port 8000
```

### Первый запуск

При первом запуске сервис загрузит модель `faster-whisper-small` (~2GB). Это может занять 5-10 минут в зависимости от скорости интернета.

**Зеркало HuggingFace:** `hf-mirror.com` используется по умолчанию для обхода корпоративных прокси.

### API

**POST /transcribe**
```bash
curl -X POST http://localhost:8000/transcribe \
  -F "file=@recording.wav"
```

**Ответ:**
```json
{
  "text": "Расшифрованный текст аудио..."
}
```

**GET /health**
```bash
curl http://localhost:8000/health
# {"status": "ok"}
```

### Конфигурация в Recorder Server

В `application.yml`:
```yaml
transcription:
  client:
    base-url: http://localhost:8000
    timeout: 60s
    auto-transcribe: false  # Автоматическая расшифровка при загрузке
```

### Использование в Telegram боте

1. Пользователь загружает аудио
2. Бот вызывает `POST /api/v1/bot/recordings/{id}/transcribe`
3. Сервер отправляет аудио в ASR сервис
4. Результат сохраняется в БД
5. Бот получает расшифровку через `GET /api/v1/bot/recordings/{id}/transcription`

---

## 2. Summarizer Сервис

### Расположение
`summarizer copy/` — сервис на базе OpenAI API (совместимый)

### Требования
- **API ключ** от `api.vsellm.ru` (или другого OpenAI-совместимого провайдера)
- Модель: `z-ai/glm-4.6v-flash` (настраивается)

### Быстрый старт

```bash
cd "summarizer copy"

# Установка зависимостей
pip install -r requirements.txt

# Создание .env файла
cat > .env << EOF
OPENAI_API_KEY=your-api-key-here
OPENAI_BASE_URL=https://api.vsellm.ru/v1
EOF

# Запуск сервера
uvicorn main:app --host 0.0.0.0 --port 8081
```

### API

**POST /summarize**
```bash
curl -X POST http://localhost:8081/summarize \
  -H "Content-Type: application/json" \
  -d '{"text": "Текст для суммаризации..."}'
```

**Ответ:**
```json
{
  "summary": "Краткая выжимка текста..."
}
```

**GET /health**
```bash
curl http://localhost:8081/health
# {"status": "ok"}
```

### Конфигурация в Recorder Server

В `application.yml`:
```yaml
summary:
  client:
    base-url: http://localhost:8081
    timeout: 30s
    api-key: ""  # Если требуется аутентификация
    auto-summarize: true  # Автоматическая суммаризация при загрузке
```

### Переменные окружения

```bash
# Для Summarizer сервиса
export OPENAI_API_KEY="your-api-key-here"
export OPENAI_BASE_URL="https://api.vsellm.ru/v1"

# Для Recorder Server
export SUMMARY_SERVICE_URL="http://localhost:8081"
export SUMMARY_API_KEY=""  # Если требуется
```

---

## 3. Интеграция с Telegram ботом

### Кнопки в боте

После загрузки аудио бот предлагает кнопки:

```
📝 Расшифровка    📊 Суммаризация
```

### Сценарий работы

#### Расшифровка:
1. Пользователь нажимает "📝 Расшифровка"
2. Бот вызывает `POST /api/v1/bot/recordings/{id}/transcribe`
3. Ожидает завершения (статус `PROCESSING` → `COMPLETED`)
4. Показывает текст расшифровки

#### Суммаризация:
1. Пользователь нажимает "📊 Суммаризация"
2. Бот вызывает `POST /api/v1/bot/recordings/{id}/summarize`
3. **Требуется API ключ** (иначе ошибка 503)
4. Показывает краткую выжимку

---

## 4. Запуск всех сервисов

### Вариант 1: По отдельности

```bash
# Терминал 1: ASR сервис
cd "asr copy" && uvicorn main:app --port 8000

# Терминал 2: Summarizer сервис
cd "summarizer copy" && uvicorn main:app --port 8081

# Терминал 3: Recorder Server
cd server && ./gradlew bootRun
```

### Вариант 2: Docker (рекомендуется для продакшена)

См. `Dockerfile` в каждой папке сервиса.

```bash
# Сборка образов
docker build -t asr-service ./asr\ copy
docker build -t summarizer-service ./summarizer\ copy
docker build -t recorder-server ./server

# Запуск через docker-compose (создать docker-compose.yml)
docker-compose up -d
```

---

## 5. Проверка интеграции

### Тестирование ASR

```bash
# 1. Проверка здоровья ASR
curl http://localhost:8000/health

# 2. Расшифровка файла
curl -X POST http://localhost:8000/transcribe \
  -F "file=@test.wav"
```

### Тестирование Summarizer

```bash
# 1. Проверка здоровья
curl http://localhost:8081/health

# 2. Суммаризация текста
curl -X POST http://localhost:8081/summarize \
  -H "Content-Type: application/json" \
  -d '{"text": "Длинный текст для суммаризации..."}'
```

### Тестирование Recorder Server

```bash
# 1. Health check
curl http://localhost:8080/api/v1/health

# 2. Загрузка аудио (через бота)
# POST /api/v1/bot/recordings/upload

# 3. Запуск расшифровки
curl -X POST http://localhost:8080/api/v1/bot/recordings/{id}/transcribe

# 4. Получение расшифровки
curl http://localhost:8080/api/v1/bot/recordings/{id}/transcription

# 5. Запуск суммаризации
curl -X POST http://localhost:8080/api/v1/bot/recordings/{id}/summarize
```

---

## 6. Troubleshooting

### ASR: "Connection refused"

```bash
# Проверьте, запущен ли ASR сервис
curl http://localhost:8000/health

# Проверьте порт в application.yml
transcription.client.base-url: http://localhost:8000
```

### Summarizer: "OPENAI_API_KEY is not set"

```bash
# Установите переменную окружения
export OPENAI_API_KEY="your-key"

# Или создайте .env файл в summarizer copy/
echo "OPENAI_API_KEY=your-key" > .env
```

### Recorder Server: "BeanCreationException"

```bash
# Проверьте, что все зависимости в build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-webflux")

# Пересоберите проект
./gradlew clean build -x test
```

### Расшифровка не сохраняется

Проверьте логи сервера:
```
Вызов ASR-сервиса для расшифровки: {id} (файл: {path})
ASR-сервис вернул расшифровку для записи: {id}
Сохранен результат расшифровки для записи: {id}
```

---

## 7. Следующие шаги

1. ✅ Интеграция ASR завершена
2. ⏸️ Summarizer готов, нужен API ключ
3. ⏸️ Telegram бот: добавить кнопки "Расшифровка" и "Суммаризация"
4. ⏸️ Docker-compose для развёртывания всех сервисов
5. ⏸️ Мониторинг и логирование запросов
