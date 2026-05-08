# Recorder — ESP32-C6 Audio Recorder Firmware

## Project Overview

**Recorder** — это модульная прошивка для ESP32-C6 (Waveshare ESP32-C6-LCD-1.47) с графическим интерфейсом и управлением одной кнопкой.

### Основные возможности

- **Режим прослушивания**: Запись аудио с микрофона (I2S) на SD-карту в формате WAV
- **Режим отправки**: WiFi-подключение и отправка записанных файлов на сервер
- **Режим трансляции**: Одновременная запись и потоковая передача на сервер
- **Графический интерфейс**: LCD-дисплей 172×344 пикселя с LovyanGFX
- **Управление**: Одна кнопка (короткое/длинное нажатие)

### Архитектура

Проект использует **ESP-IDF фреймворк** через PlatformIO с модульной архитектурой на C++17:

```
src/
├── main.cpp              # Точка входа (app_main), инициализация компонентов
├── app_state.hpp/cpp     # Глобальное состояние приложения (WiFi, счётчики)
├── button_handler.hpp/cpp# Обработка кнопки (debounce, short/long press)
├── display_handler.hpp/cpp# Управление LCD-дисплеем
├── i2s_input.hpp/cpp     # I2S микрофон (аудиовход)
├── recorder.hpp/cpp      # Управление записью WAV-файлов
├── sd_storage.hpp/cpp    # SD-карта (SPIFFS/FATFS)
├── state_processor.hpp/cpp# Машина состояний ( Waiting/Ready/Recording/Sending)
├── wifi_manager.hpp/cpp  # WiFi подключение
└── http_uploader.hpp/cpp # HTTP-загрузчик файлов на сервер
```

### Машина состояний (Recorder::State)

| Состояние | Описание |
|-----------|----------|
| `WAITING_FOR_CREDS` | Ожидание WiFi-учётных данных |
| `READY` | Готов к записи, WiFi подключён |
| `RECORDING` | Идёт запись аудио на SD-карту |
| `SENDING` | Отправка файла на сервер |

## Building and Running

### Требования

- **PlatformIO** (расширение VS Code или CLI: `pip install platformio`)
- **ESP-IDF 5.5.0** (устанавливается автоматически PlatformIO)
- **ESP32-C6 DevKitC-1** или совместимая плата

### Конфигурация

Проект имеет две предустановленные конфигурации в `platformio.ini`:

| Параметр | Значение |
|----------|----------|
| Плата | `esp32-c6-devkitc-1` |
| Фреймворк | `espidf` |
| Flash | 2MB |
| Partition Table | `partitions_2mb.csv` |
| Monitor Speed | 115200 |
| Библиотеки | LovyanGFX ^1.2.19 |

### Команды

```bash
# Сборка проекта
pio run

# Прошивка устройства
pio run --target upload

# Мониторинг последовательного порта
pio device monitor

# Сборка + прошивка + мониторинг
pio run --target upload --target monitor

# Очистка
pio run --target clean
```

### Отладка

- **Мониторинг**: `pio device monitor` (115200 бод)
- **Фильтры логов**: `esp32_exception_decoder`, `time` (автоматически в platformio.ini)
- **Self-test микрофона**: Включён при загрузке (`kRunMicSelfTestOnBoot = true` в main.cpp)

## Development Conventions

### Код-стайл

- **Язык**: C++17 (`-std=gnu++17`)
- **Именование**:
  - Классы: `PascalCase` (например, `ButtonHandler`, `StateProcessor`)
  - Методы/функции: `snake_case` (например, `run_mic_self_test`)
  - Константы: `kCamelCase` (например, `kRunMicSelfTestOnBoot`)
  - Приватные члены: `suffix_` (например, `counter_`, `wifi_connected_`)
- **Конфигурация**: Структуры `Config` с параметрами инициализации
- **Статические методы**: Для singleton-подобных компонентов (`Recorder::init()`, `Recorder::start()`)

### Архитектурные принципы

1. **Модульность**: Каждый компонент инкапсулирован в отдельный класс
2. **State Machine**: Логика разделена по состояниям в `StateProcessor`
3. **FreeRTOS**: Задачи через `vTaskDelay`, `portTICK_PERIOD_MS`
4. **ESP-IDF**: Использование нативных драйверов (`driver/gpio.h`, `freertos/task.h`)

### Критические замечания

⚠️ **Порядок инициализации**: SD-карта должна инициализироваться **перед** дисплеем, иначе SPI2 будет захвачен дисплеем и SD не сможет работать.

```cpp
// ✅ Правильно
SDStorage::init();
DisplayHandler::init();

// ❌ Неправильно
DisplayHandler::init();  // Захватывает SPI2
SDStorage::init();       // Не работает
```

### Тестирование

- **Unit-тесты**: Директория `test/` (PlatformIO Test Runner)
- **Self-test**: Встроенный тест микрофона при загрузке
- **SD-тест**: Проверка записи файла при инициализации

### Конфигурация SDK

- **`sdkconfig.defaults`**: Базовые настройки (отключение исключений C++, RTTI, WiFi PMF)
- **`sdkconfig.esp32-c6-devkitc-1`**: Автогенерируемая конфигурация ESP-IDF 5.5.0
- **`partitions_2mb.csv`**: Разметка flash-памяти (NVS, OTA, app0)

## Backend Server

В проекте имеется Spring Boot сервер для приёма и хранения аудио записей. См. [`server/README.md`](server/README.md).

### Быстрый старт сервера

```bash
cd server

# Сборка
./gradlew build -x test

# Запуск
./gradlew bootRun
```

### API Endpoints

| Endpoint | Method | Описание |
|----------|--------|----------|
| `/api/upload` | POST | Загрузка WAV-файла |
| `/api/recordings` | GET | Список всех записей |
| `/api/recordings/{id}` | GET | Метаданные записи |
| `/api/recordings/{id}/download` | GET | Скачивание файла |
| `/api/recordings/{id}` | DELETE | Удаление записи |
| `/api/stats` | GET | Статистика хранилища |
| `/api/health` | GET | Проверка здоровья |

### Интеграция с прошивкой

В `http_uploader.cpp` используйте URL:
```cpp
const char* UPLOAD_URL = "http://192.168.1.100:8080/api/upload";
HttpUploader::start_wav_upload(UPLOAD_URL, wav_path);
```

## External Resources

- **Сервер для тестирования**: `miniserver.ipynb` — Jupyter-ноутбук с Flask-сервером (порт 8080)
- **Документация ESP-IDF**: https://docs.espressif.com/projects/esp-idf/en/stable/esp32c6/
- **LovyanGFX**: https://github.com/lovyan03/LovyanGFX
- **Spring Boot**: https://spring.io/projects/spring-boot
