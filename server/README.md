# Recorder Server — Spring Boot Backend

Backend-сервер на Spring Boot для приёма, хранения и управления аудио записями с ESP32-C6 устройства с интеграцией сервиса суммаризации.

## Архитектура

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  ESP32-C6       │     │  Recorder Server │     │  Summary        │
│  Device         │────▶│  (Spring Boot)   │────▶│  Service        │
│                 │     │  Auto-summarize  │     │  (External)     │
└─────────────────┘     └──────────────────┘     └─────────────────┘
        │                       │
        │                       ▼
        │              ┌──────────────────┐
        │              │  PostgreSQL/H2   │
        │              │  Database        │
        │              └──────────────────┘
        │                       │
        │                       ▼
        │              ┌──────────────────┐
        │              │  File Storage    │
        │              │  (WAV files)     │
        │              └──────────────────┘
        │
        ▼
┌─────────────────┐
│  REST API       │
│  Clients        │
└─────────────────┘
```

**Автоматическая суммаризация**: При загрузке файла сервер автоматически запускает процесс суммаризации через внешний сервис (если `summary.client.auto-summarize: true`).

## Требования

- **Java 17+** (обязательно)
- **Gradle 8.x** (или использовать Gradle Wrapper)
- **H2** (встроена) или **PostgreSQL 14+** (для продакшена)

## Быстрый старт

### 1. Установка зависимостей

```bash
cd server

# Сборка проекта
./gradlew build -x test
```

### 2. Запуск сервера

```bash
# Запуск через Gradle
./gradlew bootRun

# Или запуск JAR-файла
java -jar build/libs/recorder-server-1.0.0-SNAPSHOT.jar
```

Сервер запустится на **http://localhost:8080**

### 3. Проверка работы

```bash
# Health check
curl http://localhost:8080/api/v1/health

# Статистика
curl http://localhost:8080/api/v1/stats
```

## Конфигурация

### application.yml

Основные настройки в `src/main/resources/application.yml`:

| Параметр | По умолчанию | Описание |
|----------|--------------|----------|
| `server.port` | 8080 | Порт сервера |
| `server.address` | 0.0.0.0 | Адрес для прослушивания |
| `spring.datasource.url` | jdbc:h2:file:./data/recorder-db | URL БД |
| `recorder.storage.path` | ./recordings | Директория для хранения WAV |
| `recorder.storage.max-file-size` | 104857600 (100MB) | Макс. размер файла |
| `summary.client.base-url` | http://localhost:8081 | URL сервиса суммаризации |
| `summary.client.timeout` | 30s | Таймаут запроса суммаризации |
| `summary.client.auto-summarize` | true | Автосуммаризация при загрузке |

### Переменные окружения

```bash
# URL сервиса суммаризации
export SUMMARY_SERVICE_URL=http://summary-service:8081

# API ключ для сервиса суммаризации
export SUMMARY_API_KEY=your-api-key-here

# Порт сервера
export SERVER_PORT=8080
```

### Продакшен конфигурация (PostgreSQL)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/recorder_db
    username: recorder_user
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
```

## API Endpoints

### 1. Загрузка записи

**POST** `/api/v1/recordings/upload`

Загрузка WAV-файла с устройства. **Автоматически запускает суммаризацию** (если включено в конфигурации).

**Request:**
```
Content-Type: multipart/form-data

file: <binary WAV file>
deviceInfo: ESP32-C6-Recorder (optional)
```

**Response (201 Created):**
```json
{
  "success": true,
  "message": "Recording uploaded successfully. Summarization started.",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "filename": "recording_20260504_120000_550e8400.wav",
    "originalFilename": "1.wav",
    "fileSize": 1024000,
    "contentType": "audio/wav",
    "deviceInfo": "ESP32-C6-Recorder",
    "deviceIp": "192.168.1.100",
    "status": "UPLOADED",
    "createdAt": "2026-05-04T12:00:00",
    "downloadUrl": "/api/v1/recordings/550e8400-e29b-41d4-a716-446655440000/download"
  },
  "meta": {
    "status": "PENDING",
    "autoSummarize": "true"
  }
}
```

### 2. Список записей (с пагинацией)

**GET** `/api/v1/recordings`

**Query Parameters:**
- `page` (default: 0) — номер страницы
- `size` (default: 20) — размер страницы
- `sortBy` (default: createdAt) — поле сортировки
- `sortDir` (default: desc) — направление (asc/desc)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "recordings": [...],
    "total": 150,
    "page": 0,
    "size": 20,
    "totalPages": 8,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

### 3. Получение записи по ID

**GET** `/api/v1/recordings/{id}`

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "filename": "...",
    "status": "READY",
    "summary": {
      "summaryText": "Текст суммаризации...",
      "briefSummary": "Краткое содержание",
      "keywords": ["ключевое1", "ключевое2"],
      "confidenceScore": 0.95,
      "detectedLanguage": "ru",
      "status": "COMPLETED"
    }
  }
}
```

### 4. Скачивание записи

**GET** `/api/v1/recordings/{id}/download`

Возвращает WAV-файл как бинарные данные.

### 5. Удаление записи

**DELETE** `/api/v1/recordings/{id}`

### 6. Запуск суммаризации

**POST** `/api/v1/recordings/{id}/summarize`

**Query Parameters:**
- `language` (optional) — код языка (ru, en)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "recordingId": "...",
    "status": "PENDING",
    "message": "Summarization started"
  }
}
```

### 7. Статус суммаризации

**GET** `/api/v1/recordings/{id}/summary/status`

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "recordingId": "...",
    "status": "PROCESSING"
  }
}
```

### 8. Статистика

**GET** `/api/v1/stats`

**Response:**
```json
{
  "success": true,
  "data": {
    "recordingsCount": 150,
    "totalSizeBytes": 157286400,
    "totalSizeMB": "150.00"
  }
}
```

### 9. Health Check

**GET** `/api/v1/health`

## Интеграция с ESP32-C6

### Пример кода прошивки

```cpp
#include "http_uploader.hpp"

// URL сервера
const char* UPLOAD_URL = "http://192.168.1.100:8080/api/v1/recordings/upload";

// После завершения записи
std::string wav_path;
if (Recorder::get_last_wav_path(wav_path)) {
    ESP_LOGI("Uploader", "Starting upload: %s", wav_path.c_str());
    
    esp_err_t err = HttpUploader::start_wav_upload(
        UPLOAD_URL, 
        wav_path.c_str()
    );
    
    if (err == ESP_OK) {
        ESP_LOGI("Uploader", "Upload started");
    } else {
        ESP_LOGE("Uploader", "Upload failed: %s", esp_err_to_name(err));
    }
}
```

### Формат multipart запроса

```
POST /api/v1/recordings/upload HTTP/1.1
Host: 192.168.1.100:8080
Content-Type: multipart/form-data; boundary=----ESP32Boundary

------ESP32Boundary
Content-Disposition: form-data; name="file"; filename="1.wav"
Content-Type: audio/wav

<binary WAV data>
------ESP32Boundary
Content-Disposition: form-data; name="deviceInfo"

ESP32-C6-Recorder
------ESP32Boundary--
```

## Интеграция с сервисом суммаризации

### Интерфейс SummaryClient

Сервер использует интерфейс `SummaryClient` для взаимодействия с внешним сервисом суммаризации:

```java
public interface SummaryClient {
    Mono<SummaryResult> summarize(String recordingId, String audioFilePath, String language);
    Mono<Boolean> cancelSummarization(String taskId);
    Mono<SummarizationStatus> getStatus(String taskId);
}
```

### Реализация через HTTP/REST

`SummaryClientImpl` использует WebClient для асинхронных запросов:

```bash
# Запрос на суммаризацию
POST http://summary-service:8081/api/v1/summarize
Content-Type: multipart/form-data
X-API-Key: your-api-key

recording_id: 550e8400-e29b-41d4-a716-446655440000
audio_file: <binary WAV>
language: ru
```

### Ответ сервиса суммаризации

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "summaryText": "Полный текст суммаризации...",
  "briefSummary": "Краткое содержание в одном предложении",
  "keywords": ["ключевое1", "ключевое2"],
  "confidenceScore": 0.95,
  "detectedLanguage": "ru",
  "status": "COMPLETED"
}
```

## Структура проекта

```
server/
├── build.gradle.kts              # Gradle конфигурация
├── settings.gradle.kts           # Настройки проекта
├── src/main/
│   ├── java/com/example/recorder/
│   │   ├── RecorderServerApplication.java
│   │   ├── client/
│   │   │   └── summary/
│   │   │       ├── SummaryClient.java      # Интерфейс клиента
│   │   │       └── SummaryClientImpl.java  # Реализация
│   │   ├── config/
│   │   │   ├── AsyncConfig.java            # Асинхронная обработка
│   │   │   ├── CorsConfig.java             # CORS настройки
│   │   │   ├── GlobalExceptionHandler.java # Обработка ошибок
│   │   │   ├── SummaryClientProperties.java # Конфигурация клиента
│   │   │   └── WebClientConfig.java        # WebClient настройка
│   │   ├── controller/
│   │   │   └── RecordingController.java    # REST API
│   │   ├── dto/
│   │   │   ├── ApiResponse.java            # Универсальный ответ
│   │   │   ├── RecordingResponse.java      # DTO записи
│   │   │   ├── SummaryResponse.java        # DTO суммаризации
│   │   │   └── UploadRecordingRequest.java # DTO запроса
│   │   ├── entity/
│   │   │   ├── RecordingEntity.java        # JPA сущность записи
│   │   │   └── SummaryEntity.java          # JPA сущность суммаризации
│   │   ├── repository/
│   │   │   ├── RecordingRepository.java    # Repository записей
│   │   │   └── SummaryRepository.java      # Repository суммаризаций
│   │   └── service/
│   │       ├── recording/
│   │       │   ├── RecordingService.java   # Интерфейс сервиса
│   │       │   └── RecordingServiceImpl.java # Реализация
│   │       └── summary/
│   │           ├── SummaryService.java     # Интерфейс суммаризации
│   │           └── SummaryServiceImpl.java # Реализация
│   └── resources/
│       ├── application.yml                 # Конфигурация
│       └── db/migration/
│           └── V1__Create_recordings_and_summaries_tables.sql
└── recordings/                             # Хранилище файлов
```

## Best Practices

### 1. Интерфейсы для всех сервисов

Все сервисы определены через интерфейсы для лучшей тестируемости:
- `RecordingService` / `RecordingServiceImpl`
- `SummaryService` / `SummaryServiceImpl`
- `SummaryClient` / `SummaryClientImpl`

### 2. Асинхронная обработка

Суммаризация выполняется асинхронно через `@Async`:
```java
@Async("summaryExecutor")
public CompletableFuture<SummarizationResult> summarizeAsync(...)
```

### 3. Транзакционность

Все операции с БД обернуты в `@Transactional`:
```java
@Transactional
public RecordingResponse uploadRecording(...)
```

### 4. Валидация DTO

Используется Bean Validation:
```java
public record UploadRecordingRequest(
    @Size(max = 255) String deviceInfo,
    @NotBlank String filename,
    Long fileSize
)
```

### 5. Централизованная обработка ошибок

`GlobalExceptionHandler` обрабатывает все исключения:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(...)
}
```

### 6. Flyway миграции

Схема БД управляется через миграции:
- `V1__Create_recordings_and_summaries_tables.sql`

## Тестирование API

### cURL примеры

```bash
# Загрузка файла
curl -X POST http://localhost:8080/api/v1/recordings/upload \
  -F "file=@test.wav" \
  -F "deviceInfo=Test-Device"

# Список записей
curl http://localhost:8080/api/v1/recordings?page=0&size=10

# Скачивание
curl -O http://localhost:8080/api/v1/recordings/{id}/download

# Запуск суммаризации
curl -X POST http://localhost:8080/api/v1/recordings/{id}/summarize \
  -d "language=ru"

# Статус суммаризации
curl http://localhost:8080/api/v1/recordings/{id}/summary/status

# Удаление
curl -X DELETE http://localhost:8080/api/v1/recordings/{id}
```

## Расширение функциональности

### Добавление новых статусов

Измените enum в `RecordingEntity.RecordingStatus`:
```java
public enum RecordingStatus {
    UPLOADED,
    ANALYZING,
    SUMMARIZING,
    SUMMARIZED,
    READY,
    FAILED,
    ARCHIVED  // Новый статус
}
```

### Кэширование

Добавьте зависимость и используйте `@Cacheable`:
```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-cache")
```

```java
@Cacheable(value = "recordings", key = "#id")
public Optional<RecordingResponse> getRecordingById(String id)
```

### S3 для хранения файлов

```java
@Service
public class S3StorageService implements FileStorageService {
    private final S3Client s3Client;
    
    public void store(String key, InputStream inputStream) {
        s3Client.putObject(PutObjectRequest.builder()
            .bucket("recordings")
            .key(key)
            .build(),
            RequestBody.fromInputStream(inputStream, size));
    }
}
```

## Troubleshooting

### Ошибка "Connection refused" к сервису суммаризации

Проверьте URL и доступность сервиса:
```bash
curl http://summary-service:8081/api/v1/health
```

### Ошибка "Database lock"

Для H2 в продакшене используйте PostgreSQL или увеличьте таймаут:
```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 30000
```

### Файлы не сохраняются

Проверьте права доступа к директории:
```bash
chmod 755 ./recordings
```

### Миграции не применяются

Проверьте логи Flyway:
```
org.flywaydb.core.internal.command.DbMigrate
```

Очистите метаданные (для dev):
```sql
DROP TABLE flyway_schema_history;
```
