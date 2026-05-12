#!/usr/bin/env bash
#
# Запуск Telegram бота для Recorder
#
# Использование:
#   ./start_bot.sh
#

set -euo pipefail

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

# Проверка .env файла
check_env() {
    if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
        log_warn "Файл .env не найден"
        log_info "Создайте файл .env с переменными:"
        echo ""
        echo "BOT_TOKEN=your_bot_token_here"
        echo "SERVER_URL=http://localhost:8080"
        echo ""
        log_info "Или выполните команду:"
        echo "cat > $SCRIPT_DIR/.env << EOF"
        echo "BOT_TOKEN=your_token"
        echo "SERVER_URL=http://localhost:8080"
        echo "EOF"
        echo ""
        read -p "Продолжить без .env? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    else
        log_info "Файл .env найден"
    fi
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
    
    if ! python3 -c "import telegram" 2>/dev/null; then
        log_info "Установка зависимостей..."
        pip install -q -r requirements.txt
    else
        log_info "Зависимости установлены"
    fi
}

# Проверка сервера
check_server() {
    log_info "Проверка доступности сервера..."
    
    if curl -s --max-time 5 "${SERVER_URL:-http://localhost:8080}/api/v1/health" > /dev/null 2>&1; then
        log_info "Сервер доступен"
    else
        log_warn "Сервер недоступен по адресу ${SERVER_URL:-http://localhost:8080}"
        log_warn "Бот будет работать, но запросы к записям не пройдут"
        log_warn "Запустите сервер: cd ../server && ./gradlew bootRun"
    fi
}

# Запуск бота
start_bot() {
    log_info "=== Запуск Telegram бота ==="
    log_info ""
    log_info "Для остановки нажмите Ctrl+C"
    log_info ""
    
    python bot.py
}

# Основная функция
main() {
    log_info "=== Telegram Bot для Recorder ==="
    echo
    
    check_python
    check_env
    setup_venv
    install_deps
    check_server
    echo
    start_bot
}

# Запуск
main "$@"
