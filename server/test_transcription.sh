#!/usr/bin/env bash
#
# Тестирование расшифровки аудио через Java-сервер
#
# Использование:
#   ./test_transcription.sh [SERVER_URL] [AUDIO_FILE]
#
# Пример:
#   ./test_transcription.sh http://localhost:8080 ./test.wav
#

set -euo pipefail

# Конфигурация
SERVER_URL="${1:-http://localhost:8080}"
API_BASE="${SERVER_URL}/api/v1"
TEST_WAV_FILE="${2:-./test.wav}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Временные файлы
TEMP_DIR="/tmp/recorder_test_$$"
mkdir -p "$TEMP_DIR"

# Логирование
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# Очистка временных файлов
cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Проверка зависимостей
check_dependencies() {
    local deps=("curl" "jq")
    local missing=()
    
    for dep in "${deps[@]}"; do
        if ! command -v "$dep" &> /dev/null; then
            missing+=("$dep")
        fi
    done
    
    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Отсутствуют зависимости: ${missing[*]}"
        log_info "Установите их: brew install ${missing[*]} (macOS) или apt-get install ${missing[*]} (Linux)"
        exit 1
    fi
}

# Проверка доступности сервера
check_server() {
    log_info "Проверка доступности сервера: $SERVER_URL"
    
    local response
    local http_code
    
    response=$(curl -s -w "\n%{http_code}" --max-time 5 "${SERVER_URL}/actuator/health" 2>/dev/null || echo -e "\n000")
    http_code=$(echo "$response" | tail -n1)
    
    if [[ "$http_code" != "200" ]] && [[ "$http_code" != "000" ]]; then
        # Пробуем другой эндпоинт
        response=$(curl -s -w "\n%{http_code}" --max-time 5 "${API_BASE}/auth/register" -X OPTIONS 2>/dev/null || echo -e "\n000")
        http_code=$(echo "$response" | tail -n1)
    fi
    
    if [[ "$http_code" == "000" ]] || [[ "$http_code" == "000" ]]; then
        log_error "Сервер недоступен по адресу $SERVER_URL"
        log_info "Убедитесь, что Java-сервер запущен: ./gradlew bootRun"
        exit 1
    fi
    
    log_info "Сервер доступен (HTTP $http_code)"
}

# Генерация уникальных данных пользователя
generate_user_data() {
    local timestamp=$(date +%s)
    local random=$((RANDOM % 10000))
    
    USER_LOGIN="test_user_${timestamp}_${random}"
    USER_PASSWORD="pass_${timestamp}_${random}"
    USERNAME="TestUser${random}"
    TELEGRAM_ID=$((900000000 + random))
    
    log_debug "Сгенерирован логин: $USER_LOGIN"
}

# Регистрация пользователя
register_user() {
    log_info "Регистрация пользователя: $USER_LOGIN"
    
    local register_data=$(cat <<EOF
{
    "telegramId": $TELEGRAM_ID,
    "username": "$USERNAME",
    "login": "$USER_LOGIN",
    "passwordHash": "$USER_PASSWORD",
    "firstName": "Test",
    "lastName": "User"
}
EOF
    )
    
    local response_file="${TEMP_DIR}/register_response.json"
    local http_code
    
    http_code=$(curl -s -w "%{http_code}" -X POST "${API_BASE}/auth/register" \
        -H "Content-Type: application/json" \
        -d "$register_data" \
        -o "$response_file")
    
    if [[ "$http_code" != "201" ]] && [[ "$http_code" != "200" ]]; then
        log_error "Регистрация не удалась (HTTP $http_code)"
        cat "$response_file" >&2
        return 1
    fi
    
    local success=$(jq -r '.success // false' "$response_file" 2>/dev/null)
    if [[ "$success" != "true" ]]; then
        local message=$(jq -r '.message // "Unknown error"' "$response_file")
        log_error "Ошибка регистрации: $message"
        return 1
    fi
    
    log_info "Пользователь успешно зарегистрирован"
    return 0
}

# Логин пользователя
login_user() {
    log_info "Вход в систему: $USER_LOGIN"
    
    local login_data=$(cat <<EOF
{
    "login": "$USER_LOGIN",
    "passwordHash": "$USER_PASSWORD"
}
EOF
    )
    
    local response_file="${TEMP_DIR}/login_response.json"
    local http_code
    
    http_code=$(curl -s -w "%{http_code}" -X POST "${API_BASE}/auth/login" \
        -H "Content-Type: application/json" \
        -d "$login_data" \
        -o "$response_file")
    
    if [[ "$http_code" != "200" ]]; then
        log_error "Логин не удался (HTTP $http_code)"
        cat "$response_file" >&2
        return 1
    fi
    
    AUTH_TOKEN=$(jq -r '.data.token // empty' "$response_file" 2>/dev/null)
    
    if [[ -z "$AUTH_TOKEN" ]] || [[ "$AUTH_TOKEN" == "null" ]]; then
        log_error "Токен не получен"
        cat "$response_file" >&2
        return 1
    fi
    
    log_info "Токен получен: ${AUTH_TOKEN:0:20}..."
    return 0
}

# Проверка файла
check_audio_file() {
    local file_path="$1"
    
    if [[ ! -f "$file_path" ]]; then
        log_error "Файл не найден: $file_path"
        return 1
    fi
    
    local file_size=$(stat -f%z "$file_path" 2>/dev/null || stat -c%s "$file_path" 2>/dev/null)
    local file_size_mb=$(echo "scale=2; $file_size / 1048576" | bc)
    
    log_info "Файл: $(basename "$file_path")"
    log_info "Размер: ${file_size_mb} MB ($file_size байт)"
    
    # Проверка типа файла
    local file_type=$(file --mime-type -b "$file_path" 2>/dev/null || echo "unknown")
    log_debug "MIME тип: $file_type"
    
    if [[ $file_size -gt 104857600 ]]; then  # 100 MB
        log_warn "Файл очень большой (>100 MB), загрузка может занять время"
    fi
    
    return 0
}

# Загрузка аудиофайла с правильной обработкой
upload_recording() {
    local token="$1"
    local file_path="$2"
    
    log_info "Загрузка аудиофайла..."
    
    local response_file="${TEMP_DIR}/upload_response.json"
    local headers_file="${TEMP_DIR}/upload_headers.txt"
    local http_code
    
    # Загрузка с явным указанием Content-Type
    http_code=$(curl -s -w "%{http_code}" -X POST "${API_BASE}/bot/recordings/upload" \
        -H "Authorization: Bearer $token" \
        -F "file=@$file_path;type=audio/wav" \
        -F "deviceInfo=Bash-Test-Script" \
        --max-time 120 \
        --connect-timeout 30 \
        -D "$headers_file" \
        -o "$response_file" \
        2>&1)
    
    log_debug "HTTP код: $http_code"
    
    if [[ "$http_code" != "201" ]] && [[ "$http_code" != "200" ]]; then
        log_error "Загрузка не удалась (HTTP $http_code)"
        
        if [[ -f "$response_file" ]]; then
            local error_msg=$(jq -r '.message // .error // "Unknown error"' "$response_file" 2>/dev/null)
            log_error "Ошибка: $error_msg"
            cat "$response_file" >&2
        fi
        return 1
    fi
    
    # Парсим ответ
    RECORDING_ID=$(jq -r '.data.id // empty' "$response_file" 2>/dev/null)
    
    if [[ -z "$RECORDING_ID" ]] || [[ "$RECORDING_ID" == "null" ]]; then
        log_error "ID записи не получен"
        cat "$response_file" >&2
        return 1
    fi
    
    log_info "Запись загружена, ID: $RECORDING_ID"
    return 0
}

# Запуск расшифровки
start_transcription() {
    local token="$1"
    local recording_id="$2"
    
    log_info "Запуск расшифровки для записи: $recording_id"
    
    local response_file="${TEMP_DIR}/transcribe_start.json"
    local http_code
    
    http_code=$(curl -s -w "%{http_code}" -X POST "${API_BASE}/bot/recordings/${recording_id}/transcribe" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -o "$response_file")
    
    if [[ "$http_code" != "200" ]]; then
        log_error "Не удалось запустить расшифровку (HTTP $http_code)"
        cat "$response_file" >&2
        return 1
    fi
    
    local status=$(jq -r '.data.status // "UNKNOWN"' "$response_file" 2>/dev/null)
    local message=$(jq -r '.data.message // "Started"' "$response_file" 2>/dev/null)
    
    log_info "Статус: $status"
    log_debug "Сообщение: $message"
    
    return 0
}

# Получение результата расшифровки
get_transcription() {
    local token="$1"
    local recording_id="$2"
    local max_attempts="${3:-20}"
    local delay="${4:-3}"
    
    log_info "Ожидание результата расшифровки (макс. $max_attempts попыток, интервал ${delay}с)..."
    
    local attempt=1
    local status="PENDING"
    local transcription_text=""
    local brief_text=""
    
    while [[ $attempt -le $max_attempts ]]; do
        echo -ne "\r${BLUE}[Попытка $attempt/$max_attempts]${NC} " >&2
        
        local response_file="${TEMP_DIR}/transcription_${attempt}.json"
        local http_code
        
        http_code=$(curl -s -w "%{http_code}" -X GET "${API_BASE}/bot/recordings/${recording_id}/transcription" \
            -H "Authorization: Bearer $token" \
            -o "$response_file")
        
        if [[ "$http_code" == "200" ]]; then
            status=$(jq -r '.data.status // "UNKNOWN"' "$response_file" 2>/dev/null)
            transcription_text=$(jq -r '.data.transcriptionText // ""' "$response_file" 2>/dev/null)
            brief_text=$(jq -r '.data.briefText // ""' "$response_file" 2>/dev/null)
            local confidence=$(jq -r '.data.confidenceScore // "0"' "$response_file" 2>/dev/null)
            
            echo -e " Статус: $status, Уверенность: ${confidence}%" >&2
            
            if [[ "$status" == "COMPLETED" ]] || [[ "$status" == "SUCCESS" ]]; then
                log_info "Расшифровка завершена успешно!"
                break
            elif [[ "$status" == "FAILED" ]] || [[ "$status" == "ERROR" ]]; then
                local error_msg=$(jq -r '.data.errorMessage // "Unknown error"' "$response_file")
                log_error "Ошибка расшифровки: $error_msg"
                return 1
            fi
        elif [[ "$http_code" == "404" ]]; then
            echo -e " Расшифровка ещё не готова (404)" >&2
        else
            echo -e " Неожиданный HTTP код: $http_code" >&2
        fi
        
        ((attempt++))
        sleep "$delay"
    done
    
    echo "" >&2
    
    if [[ "$status" != "COMPLETED" ]] && [[ "$status" != "SUCCESS" ]]; then
        log_warn "Расшифровка не завершена после $max_attempts попыток"
        log_warn "Текущий статус: $status"
        return 1
    fi
    
    # Сохраняем результат
    TRANSCRIPTION_TEXT="$transcription_text"
    BRIEF_TEXT="$brief_text"
    
    return 0
}

# Получение списка записей
get_recordings_list() {
    local token="$1"
    
    log_info "Получение списка записей..."
    
    local response_file="${TEMP_DIR}/recordings_list.json"
    local http_code
    
    http_code=$(curl -s -w "%{http_code}" -X GET "${API_BASE}/bot/recordings" \
        -H "Authorization: Bearer $token" \
        -o "$response_file")
    
    if [[ "$http_code" != "200" ]]; then
        log_warn "Не удалось получить список записей (HTTP $http_code)"
        return 1
    fi
    
    local count=$(jq '.data | length' "$response_file" 2>/dev/null || echo "0")
    log_info "Всего записей: $count"
    
    return 0
}

# Очистка тестовых данных (опционально)
cleanup_test_data() {
    local token="$1"
    local recording_id="$2"
    
    log_info "Очистка тестовых данных..."
    
    # Удаляем запись
    local http_code
    http_code=$(curl -s -w "%{http_code}" -X DELETE "${API_BASE}/bot/recordings/${recording_id}" \
        -H "Authorization: Bearer $token" \
        -o /dev/null)
    
    if [[ "$http_code" == "200" ]]; then
        log_debug "Запись удалена"
    else
        log_warn "Не удалось удалить запись (HTTP $http_code)"
    fi
}

# Вывод результата
print_result() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║                     РЕЗУЛЬТАТ РАСШИФРОВКИ                    ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    if [[ -n "$BRIEF_TEXT" ]] && [[ "$BRIEF_TEXT" != "null" ]]; then
        echo -e "${YELLOW}📝 КРАТКОЕ СОДЕРЖАНИЕ:${NC}"
        echo "────────────────────────────────────────────────────────"
        echo "$BRIEF_TEXT"
        echo "────────────────────────────────────────────────────────"
        echo ""
    fi
    
    if [[ -n "$TRANSCRIPTION_TEXT" ]] && [[ "$TRANSCRIPTION_TEXT" != "null" ]]; then
        echo -e "${YELLOW}📄 ПОЛНЫЙ ТЕКСТ РАСШИФРОВКИ:${NC}"
        echo "────────────────────────────────────────────────────────"
        echo "$TRANSCRIPTION_TEXT"
        echo "────────────────────────────────────────────────────────"
        echo ""
    else
        log_warn "Текст расшифровки отсутствует"
    fi
    
    echo -e "${GREEN}✓ ID записи:${NC} $RECORDING_ID"
    echo ""
    echo -e "${BLUE}Для проверки статуса:${NC}"
    echo "  curl -H \"Authorization: Bearer $AUTH_TOKEN\" \\"
    echo "    ${API_BASE}/bot/recordings/${RECORDING_ID}/transcription"
    echo ""
}

# Основная функция
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║           ТЕСТИРОВАНИЕ РАСШИФРОВКИ АУДИО (Java-сервер)      ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    # Проверка зависимостей
    check_dependencies
    
    # Проверка доступности сервера
    check_server
    
    # Проверка аудиофайла
    if ! check_audio_file "$TEST_WAV_FILE"; then
        log_error "Аудиофайл не найден или повреждён: $TEST_WAV_FILE"
        log_info "Создайте тестовый файл или укажите путь: $0 [URL] [ФАЙЛ]"
        exit 1
    fi
    
    # Генерация данных пользователя
    generate_user_data
    
    # Регистрация и логин
    if ! register_user; then
        log_error "Не удалось зарегистрировать пользователя"
        exit 1
    fi
    
    if ! login_user; then
        log_error "Не удалось выполнить вход"
        exit 1
    fi
    
    echo ""
    
    # Загрузка файла
    if ! upload_recording "$AUTH_TOKEN" "$TEST_WAV_FILE"; then
        log_error "Не удалось загрузить файл"
        exit 1
    fi
    
    echo ""
    
    # Запуск расшифровки
    if ! start_transcription "$AUTH_TOKEN" "$RECORDING_ID"; then
        log_error "Не удалось запустить расшифровку"
        exit 1
    fi
    
    echo ""
    
    # Получение результата
    if get_transcription "$AUTH_TOKEN" "$RECORDING_ID" 25 3; then
        print_result
    else
        log_error "Не удалось получить результат расшифровки"
        exit 1
    fi
    
    # Дополнительно: показать список записей
    echo ""
    get_recordings_list "$AUTH_TOKEN"
    
    # Очистка (раскомментируйте если нужно автоматически удалять)
    # echo ""
    # cleanup_test_data "$AUTH_TOKEN" "$RECORDING_ID"
    
    log_info "Тестирование завершено!"
    exit 0
}

# Запуск main с обработкой ошибок
if ! main "$@"; then
    log_error "Скрипт завершился с ошибкой"
    exit 1
fi