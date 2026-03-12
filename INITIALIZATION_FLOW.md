# Поток инициализации и управления состоянием

## 🚀 Порядок инициализации при запуске

### 1. **Глобальная инициализация состояния Recorder**

**Файл:** `src/recorder.cpp`
```cpp
Recorder::State Recorder::state = Recorder::WAITING_FOR_CREDS;  // Initialize state
```

✅ Это происходит **ДО** вызова `app_main()`
✅ Состояние **всегда** инициализируется с `WAITING_FOR_CREDS`
✅ Это гарантированное начальное состояние при каждом включении устройства

---

### 2. **Инициализация в main.cpp**

**Порядок:**
```cpp
[1] Initializing SD card
    └─ SDStorage::init() → успех или ошибка

[2] Initializing display
    └─ DisplayHandler инициализируется

[3] Running SD self-test
    └─ Проверка записи на SD карту

[4] Initializing recorder
    └─ Recorder::init() → инициализирует I2S, проверяет директории
    └─ Recorder::state остаётся WAITING_FOR_CREDS

[5] Initializing state processor
    └─ StateProcessor::StateProcessor()
       ├─ Инициализирует WiFiManager
       ├─ Устанавливает last_state = WAITING_FOR_CREDS
       └─ Установит g_creds_file_checked = false

[6] Entering main loop
    └─ while (true) { state_processor.process(); }
```

---

## 🔄 Цикл обработки состояния WAITING_FOR_CREDS

### Первая итерация (первый вызов process()):

```
Recorder::state = WAITING_FOR_CREDS ← инициализировано в Recorder::init()
                                      ↓
StateProcessor::process()
  │
  ├─ should_process() → true (интервал прошёл)
  │
  ├─ Recorder::state == WAITING_FOR_CREDS
  │
  └─ process_waiting_for_creds()
     │
     ├─ if (!g_creds_file_checked) → true (первый раз)
     │  │
     │  ├─ Читает /sdcard/.creds
     │  │
     │  ├─ ЕСЛИ файл НЕ существует:
     │  │  ├─ Создаёт пустой файл
     │  │  └─ Запускает AP "SETUP_ME"
     │  │
     │  ├─ ЕСЛИ файл пустой:
     │  │  └─ Запускает AP "SETUP_ME"
     │  │
     │  └─ ЕСЛИ файл содержит сети:
     │     ├─ Парсит SSID:Password пары
     │     └─ Подготавливает список для подключения
     │
     ├─ g_creds_file_checked = true (больше не повторяется)
     │
     ├─ Пытается подключиться к первой сети (если есть)
     │
     └─ Проверяет статус подключения
        └─ Если подключено → Recorder::state = READY
```

### Последующие итерации:

```
Recorder::state = WAITING_FOR_CREDS
                                      ↓
StateProcessor::process()
  │
  ├─ should_process() → true/false (зависит от интервала)
  │
  └─ if (should_process())
     │
     └─ process_waiting_for_creds()
        │
        ├─ g_creds_file_checked == true → пропускает парсинг
        │
        ├─ Пытается подключиться к следующей сети
        │
        └─ Проверяет статус подключения
           └─ Если подключено → Recorder::state = READY
```

---

## 📊 Временная шкала выполнения

### На сроке инициализации (0-100ms):

```
0ms    SD Card init
       Display init
       Recorder init (Recorder::state = WAITING_FOR_CREDS)
       StateProcessor init (last_state = WAITING_FOR_CREDS)
       
10ms   Main loop starts
       state_processor.process() #1
       │
       ├─ Записано состояние WAITING_FOR_CREDS
       ├─ Вызвана process_waiting_for_creds()
       ├─ Прочитан .creds файл
       └─ Начата попытка подключения WiFi
       
20ms   vTaskDelay(10ms) → ждём

30ms   state_processor.process() #2
       │
       ├─ Интервал 100ms ещё не прошёл → should_process() = false
       └─ Пропускает обработку
       
40ms   vTaskDelay(10ms) → ждём
...

110ms  state_processor.process() #11
       │
       ├─ Интервал 100ms прошёл → should_process() = true
       ├─ process_waiting_for_creds()
       ├─ Проверка статуса WiFi
       └─ Попытка подключиться к следующей сети (если есть)
```

---

## 🎯 Гарантии

✅ **Состояние ВСЕГДА начинается с WAITING_FOR_CREDS**
- Статическая инициализация в `Recorder::state`
- Не зависит от порядка инициализации других компонентов
- Не может быть перезаписано случайно

✅ **StateProcessor отслеживает переходы**
- `last_state_` инициализируется с `WAITING_FOR_CREDS`
- При первом вызове `process()` логирует переход (даже если нет изменений)
- Отслеживает все последующие переходы между состояниями

✅ **Парсинг .creds происходит только один раз**
- `g_creds_file_checked` флаг предотвращает повторное чтение
- При необходимости обновления нужна перезагрузка устройства

✅ **Попытки подключения циклятся**
- После каждого интервала (100ms) пытается следующую сеть
- После исчерпания всех сетей остаётся в AP режиме
- WiFi статус проверяется постоянно

---

## 🔧 Как изменить начальное состояние (если понадобится)

Если в будущем нужно начинать с другого состояния:

**Вариант 1: Измени инициализацию в Recorder::state**
```cpp
// src/recorder.cpp
Recorder::State Recorder::state = Recorder::READY;  // Начинать с READY
```

**Вариант 2: Измени инициализацию в StateProcessor**
```cpp
// src/state_processor.cpp
StateProcessor::StateProcessor(const Config& cfg)
    : config_(cfg), 
      last_state_(Recorder::READY),  // Ожидать READY
      last_process_time_ms_(0) {
    // ...
}
```

**Вариант 3: Измени начальное состояние в main.cpp**
```cpp
// src/main.cpp после инициализации Recorder
Recorder::state = Recorder::READY;  // Явно установить
```

---

## 📝 Логирование инициализации

Ожидаемый вывод на консоли при запуске:

```
=== ESP32-C6 Recorder Boot ===

[1] Initializing SD card...
✅ SD init...

[2] Initializing display...
[3] Running SD self-test...
✅ SD self-test PASSED

[4] Initializing recorder...
✅ Recorder: OK

=== SYSTEM READY ===

[5] Initializing state processor...
StateProcessor: initialized with interval 100 ms
StateProcessor: WiFi manager initialized
✅ State processor initialized

[6] Entering main loop...
StateProcessor: WAITING_FOR_CREDS - checking .creds file
StateProcessor: .creds file not found, creating empty file
StateProcessor: Starting AP mode (SETUP_ME)
```

---

## ✅ Проверочный список

- [x] Recorder::state инициализируется с WAITING_FOR_CREDS
- [x] StateProcessor инициализируется с WAITING_FOR_CREDS
- [x] Первый вызов process() срабатывает в течение 100ms
- [x] .creds файл проверяется только один раз при инициализации
- [x] WiFi AP режим запускается если .creds пустой или не существует
- [x] WiFi подключение попытается для каждой строки в .creds
- [x] При успешном подключении состояние переходит в READY
- [x] Все операции логируются на консоль
