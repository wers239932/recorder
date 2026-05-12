#!/usr/bin/env bash
#
# Запуск ASR-сервиса (faster-whisper) через зеркало HuggingFace
#
# Использование:
#   ./start_asr.sh [PORT]
#
# Пример:
#   ./start_asr.sh 8000
#

set -euo pipefail

# Конфигурация
PORT="${1:-8000}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Проверка Python 3
check_python() {
    if ! command -v python3 &> /dev/null; then
        log_error "Python 3 не найден"
        exit 1
    fi
    
    log_info "Python: $(python3 --version)"
}

# Создание виртуального окружения
setup_venv() {
    if [[ ! -d "venv" ]]; then
        log_info "Создание виртуального окружения..."
        python3 -m venv venv
    else
        log_info "Виртуальное окружение найдено"
    fi
    
    source venv/bin/activate
}

# Установка зависимостей
install_deps() {
    log_info "Проверка зависимостей..."
    
    if ! python3 -c "import fastapi" 2>/dev/null; then
        log_info "Установка зависимостей (это может занять несколько минут)..."
        pip install -q fastapi uvicorn python-multipart faster-whisper
    else
        log_info "Зависимости установлены"
    fi
}

# Запуск сервера
start_server() {
    local port="$1"
    
    log_info "=== Запуск ASR-сервиса ==="
    log_info "Порт: $port"
    log_info "Зеркало HuggingFace: https://hf-mirror.com"
    log_info ""
    log_info "Первый запуск может занять время (загрузка модели ~2GB)"
    log_info ""
    
    # Установка переменной окружения для зеркала
    export HF_ENDPOINT="https://hf-mirror.com"
    
    log_info "HF_ENDPOINT=$HF_ENDPOINT"
    log_info ""
    
    # Запуск uvicorn
    uvicorn main:app --host 0.0.0.0 --port "$port"
}

# Основная функция
main() {
    log_info "=== ASR-сервис (faster-whisper) ==="
    echo
    
    check_python
    setup_venv
    install_deps
    echo
    start_server "$PORT"
}

# Запуск
main "$@"
