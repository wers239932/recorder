#!/usr/bin/env bash
#
# Тестирование суммаризации аудио через Java-сервер
#
# Использование:
#   ./test_summarization.sh [SERVER_URL] [AUDIO_FILE]
#

set -euo pipefail

SERVER_URL="${1:-http://localhost:8080}"
API_BASE="${SERVER_URL}/api/v1"
TEST_WAV_FILE="${2:-./test.wav}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

TEMP_DIR="/tmp/recorder_summarization_test_$$"
mkdir -p "$TEMP_DIR"

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_debug() { echo -e "${BLUE}[DEBUG]${NC} $1"; }

cleanup() { rm -rf "$TEMP_DIR"; }
trap cleanup EXIT

check_dependencies() {
    local deps=("curl" "jq")
    for dep in "${deps[@]}"; do
        if ! command -v "$dep" &> /dev/null; then
            log_error "Отсутствует зависимость: $dep"
            exit 1
        fi
    done
}

generate_user_data() {
    local timestamp=$(date +%s)
    local random=$((RANDOM % 10000))
    USER_LOGIN="sum_test_${timestamp}_${random}"
    USER_PASSWORD="pass_${timestamp}"
    TELEGRAM_ID=$((900000000 + random))
}

register_user() {
    log_info "Регистрация пользователя: $USER_LOGIN"
    local response_file="${TEMP_DIR}/register.json"
    
    curl -s -X POST "${API_BASE}/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"telegramId\":$TELEGRAM_ID,\"username\":\"SumTest\",\"login\":\"$USER_LOGIN\",\"passwordHash\":\"$USER_PASSWORD\",\"firstName\":\"Sum\",\"lastName\":\"Test\"}" \
        -o "$response_file"
    
    if ! jq -e '.success == true' "$response_file" > /dev/null 2>&1; then
        log_error "Регистрация не удалась"
        cat "$response_file" >&2
        return 1
    fi
    log_info "Пользователь зарегистрирован"
}

login_user() {
    log_info "Вход в систему"
    local response_file="${TEMP_DIR}/login.json"
    
    curl -s -X POST "${API_BASE}/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"login\":\"$USER_LOGIN\",\"passwordHash\":\"$USER_PASSWORD\"}" \
        -o "$response_file"
    
    AUTH_TOKEN=$(jq -r '.data.token' "$response_file")
    if [[ -z "$AUTH_TOKEN" ]] || [[ "$AUTH_TOKEN" == "null" ]]; then
        log_error "Токен не получен"
        exit 1
    fi
    log_info "Токен получен"
}

upload_recording() {
    log_info "Загрузка аудиофайла..."
    local response_file="${TEMP_DIR}/upload.json"
    
    curl -s -X POST "${API_BASE}/bot/recordings/upload" \
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -F "file=@$TEST_WAV_FILE;type=audio/wav" \
        -F "deviceInfo=Bash-Summarization-Test" \
        -o "$response_file"
    
    RECORDING_ID=$(jq -r '.data.id' "$response_file")
    if [[ -z "$RECORDING_ID" ]] || [[ "$RECORDING_ID" == "null" ]]; then
        log_error "Не удалось загрузить файл"
        exit 1
    fi
    log_info "Запись загружена, ID: $RECORDING_ID"
}

start_transcription() {
    log_info "Запуск транскрипции..."
    curl -s -X POST "${API_BASE}/bot/recordings/${RECORDING_ID}/transcribe" \
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -o /dev/null
    log_info "Транскрипция запущена"
}

wait_transcription() {
    log_info "Ожидание завершения транскрипции..."
    local max_attempts=30
    local attempt=1
    
    while [[ $attempt -le $max_attempts ]]; do
        local response_file="${TEMP_DIR}/transcription_${attempt}.json"
        curl -s -X GET "${API_BASE}/bot/recordings/${RECORDING_ID}/transcription" \
            -H "Authorization: Bearer $AUTH_TOKEN" \
            -o "$response_file"
        
        local status=$(jq -r '.data.status // "PENDING"' "$response_file")
        
        if [[ "$status" == "COMPLETED" ]]; then
            log_info "Транскрипция завершена"
            return 0
        elif [[ "$status" == "FAILED" ]]; then
            log_error "Транскрипция не удалась"
            return 1
        fi
        
        echo -ne "\r[Попытка $attempt/$max_attempts] Статус: $status"
        ((attempt++))
        sleep 2
    done
    
    log_error "Транскрипция не завершилась за $max_attempts попыток"
    return 1
}

start_summarization() {
    log_info "Запуск суммаризации..."
    local response_file="${TEMP_DIR}/summarize_start.json"
    
    curl -s -X POST "${API_BASE}/bot/recordings/${RECORDING_ID}/summarize" \
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -o "$response_file"
    
    log_info "Суммаризация запущена"
}

wait_summarization() {
    log_info "Ожидание завершения суммаризации..."
    local max_attempts=30
    local attempt=1
    
    while [[ $attempt -le $max_attempts ]]; do
        local response_file="${TEMP_DIR}/summary_${attempt}.json"
        curl -s -X GET "${API_BASE}/bot/recordings/${RECORDING_ID}/summary" \
            -H "Authorization: Bearer $AUTH_TOKEN" \
            -o "$response_file" 2>/dev/null || true
        
        if [[ -f "$response_file" ]] && jq -e '.data.summaryText' "$response_file" > /dev/null 2>&1; then
            local summary_text=$(jq -r '.data.summaryText' "$response_file")
            if [[ -n "$summary_text" ]] && [[ "$summary_text" != "null" ]]; then
                log_info "Суммаризация завершена"
                SUMMARY_TEXT="$summary_text"
                return 0
            fi
        fi
        
        echo -ne "\r[Попытка $attempt/$max_attempts] Ожидание..."
        ((attempt++))
        sleep 2
    done
    
    log_error "Суммаризация не завершилась за $max_attempts попыток"
    return 1
}

print_result() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║                  РЕЗУЛЬТАТ СУММАРИЗАЦИИ                      ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    if [[ -n "$SUMMARY_TEXT" ]] && [[ "$SUMMARY_TEXT" != "null" ]]; then
        echo -e "${YELLOW}📊 СУММАРИЗАЦИЯ:${NC}"
        echo "────────────────────────────────────────────────────────"
        echo "$SUMMARY_TEXT"
        echo "────────────────────────────────────────────────────────"
    else
        log_error "Текст суммаризации отсутствует"
    fi
    
    echo ""
    echo -e "${GREEN}✓ ID записи:${NC} $RECORDING_ID"
}

main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║        ТЕСТИРОВАНИЕ СУММАРИЗАЦИИ АУДИО (Java-сервер)        ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    check_dependencies
    generate_user_data
    register_user || exit 1
    login_user || exit 1
    upload_recording || exit 1
    start_transcription || exit 1
    wait_transcription || exit 1
    start_summarization || exit 1
    wait_summarization || exit 1
    print_result
    
    log_info "Тестирование завершено успешно!"
}

main "$@"
