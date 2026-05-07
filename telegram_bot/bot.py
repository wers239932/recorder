#!/usr/bin/env python3
import asyncio
import logging
import os
import re
import hashlib
from datetime import datetime
from typing import Dict, Optional, List

from telegram import (
    Update,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    ReplyKeyboardMarkup,
    KeyboardButton,
    CallbackQuery,
)
from telegram.ext import (
    Application,
    CommandHandler,
    MessageHandler,
    CallbackQueryHandler,
    ConversationHandler,
    ContextTypes,
    filters,
)
import requests
from dotenv import load_dotenv

# Загрузка переменных окружения
load_dotenv()

# Настройки
BOT_TOKEN = os.getenv("BOT_TOKEN")
SERVER_URL = os.getenv("SERVER_URL", "http://localhost:8080")
API_BASE = f"{SERVER_URL}/api/v1"

# Состояния для ConversationHandler
LOGIN, LOGIN_PASSWORD, REGISTER, REGISTER_PASSWORD, RENAME = range(5)

# Хранилище авторизованных пользователей
authorized_users: Dict[int, str] = {}

# Настройка логирования
logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


class TelegramBot:
    def __init__(self):
        self.server_url = SERVER_URL
        self.api_base = API_BASE

    def hash_password(self, password: str) -> str:
        """Хеширование пароля SHA-256"""
        return hashlib.sha256(password.encode()).hexdigest()

    def is_authorized(self, user_id: int) -> bool:
        """Проверка авторизации пользователя"""
        return user_id in authorized_users

    def get_user_token(self, user_id: int) -> Optional[str]:
        """Получение токена пользователя"""
        return authorized_users.get(user_id)

    async def check_authorization(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
        """Проверка авторизации и отправка сообщения если не авторизован"""
        user_id = update.effective_user.id
        if not self.is_authorized(user_id):
            await update.message.reply_text(
                "🚫 Доступ запрещен!\n\n"
                "Для использования этой функции необходимо войти в аккаунт.\n"
                "Используйте команду /login для входа или /register для регистрации."
            )
            return False
        return True

    # API методы
    def api_request(self, method: str, endpoint: str, token: str = None, data: dict = None, params: dict = None) -> dict:
        """Универсальный метод для API запросов"""
        url = f"{self.api_base}{endpoint}"
        headers = {"Content-Type": "application/json"}

        if token:
            headers["Authorization"] = f"Bearer {token}"

        try:
            if method.upper() == "GET":
                response = requests.get(url, headers=headers, params=params, timeout=30)
            elif method.upper() == "POST":
                response = requests.post(url, headers=headers, json=data, timeout=30)
            elif method.upper() == "PUT":
                response = requests.put(url, headers=headers, json=data, timeout=30)
            elif method.upper() == "DELETE":
                response = requests.delete(url, headers=headers, timeout=30)
            else:
                raise ValueError(f"Неподдерживаемый метод HTTP: {method}")

            response.raise_for_status()
            return response.json()

        except requests.exceptions.RequestException as e:
            logger.error(f"API request failed: {e}")
            raise Exception(f"Ошибка сервера: {e}")

    async def start_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Обработчик команды /start"""
        user = update.effective_user
        welcome_text = (
            f"👋 Привет, {user.first_name}!\n\n"
            "Я - бот для управления аудиозаписями.\n\n"
            "🎯 Основные возможности:\n"
            "• Регистрация и вход в аккаунт\n"
            "• Просмотр списка ваших аудиозаписей\n"
            "• Скачивание, переименование, удаление записей\n"
            "• Получение краткого содержания (суммаризация)\n"
            "• Текстовая расшифровка аудио\n\n"
            "📋 Доступные команды:\n"
            "/start - это сообщение\n"
            "/register - регистрация нового аккаунта\n"
            "/login - вход в аккаунт\n"
            "/logout - выход из аккаунта\n"
            "/recordings - список ваших аудиозаписей\n"
            "/help - справка\n\n"
            "🔐 Для начала работы необходимо зарегистрироваться или войти в аккаунт."
        )

        keyboard = [
            [KeyboardButton("/register"), KeyboardButton("/login")],
            [KeyboardButton("/help"), KeyboardButton("/recordings")]
        ]
        reply_markup = ReplyKeyboardMarkup(keyboard, resize_keyboard=True)

        await update.message.reply_text(welcome_text, reply_markup=reply_markup)

    async def help_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Обработчик команды /help"""
        help_text = (
            "📖 Справка по командам:\n\n"
            "🔐 Авторизация:\n"
            "/register - создать новый аккаунт\n"
            "/login - войти в существующий аккаунт\n"
            "/logout - выйти из аккаунта\n\n"
            "📼 Работа с записями (требуется авторизация):\n"
            "/recordings - список всех ваших аудиозаписей\n"
            "При нажатии на конкретную запись доступны:\n"
            "• 📥 Скачать\n"
            "• 📝 Переименовать\n"
            "• ✂️ Удалить\n"
            "• 📋 Краткое содержание\n"
            "• 📄 Текстовая расшифровка\n\n"
            "💡 Советы:\n"
            "• Все операции с записями выполняются только для ваших файлов\n"
            "• Сессия истекает через 24 часа\n"
            "• Используйте уникальные логины при регистрации"
        )

        await update.message.reply_text(help_text)

    # Регистрация и авторизация
    async def register_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Начало процесса регистрации"""
        user_id = update.effective_user.id

        if self.is_authorized(user_id):
            await update.message.reply_text(
                "❌ Вы уже авторизованы!\n"
                "Используйте /logout для выхода, затем /register для новой регистрации."
            )
            return ConversationHandler.END

        await update.message.reply_text(
            "🔐 Регистрация нового аккаунта\n\n"
            "Введите желаемый логин (латиница, 3-20 символов):"
        )

        return REGISTER

    async def process_register_login(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Обработка логина при регистрации"""
        login = update.message.text.strip()

        if len(login) < 3 or len(login) > 20:
            await update.message.reply_text(
                "❌ Логин должен содержать от 3 до 20 символов!\n"
                "Попробуйте еще раз:"
            )
            return REGISTER

        if not re.match(r'^[a-zA-Z0-9_]+$', login):
            await update.message.reply_text(
                "❌ Логин может содержать только латинские буквы, цифры и символ '_'\n"
                "Попробуйте еще раз:"
            )
            return REGISTER

        try:
            context.user_data["register_login"] = login
            await update.message.reply_text(
                f"✅ Логин '{login}' принят!\n\n"
                f"Теперь введите пароль (минимум 6 символов):"
            )
            return REGISTER_PASSWORD

        except Exception as e:
            await update.message.reply_text(f"❌ Ошибка: {e}\nПопробуйте другой логин:")
            return REGISTER

    async def process_register_password(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Обработка пароля при регистрации"""
        password = update.message.text.strip()

        if len(password) < 6:
            await update.message.reply_text(
                "❌ Пароль должен содержать минимум 6 символов!\n"
                "Попробуйте еще раз:"
            )
            return REGISTER_PASSWORD

        login = context.user_data["register_login"]
        user = update.effective_user

        try:
            data = {
                "telegramId": user.id,
                "username": user.username,
                "login": login,
                "passwordHash": self.hash_password(password),
                "firstName": user.first_name,
                "lastName": user.last_name
            }

            result = self.api_request("POST", "/auth/register", data=data)
            del context.user_data["register_login"]

            await update.message.reply_text(
                f"✅ Регистрация успешна!\n\n"
                f"Ваш логин: {login}\n"
                f"Теперь вы можете войти в аккаунт командой /login"
            )

            return ConversationHandler.END

        except Exception as e:
            await update.message.reply_text(f"❌ Ошибка регистрации: {e}\nПопробуйте другой логин:")
            return REGISTER

    async def login_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Начало процесса входа"""
        user_id = update.effective_user.id

        if self.is_authorized(user_id):
            await update.message.reply_text(
                "❌ Вы уже авторизованы!\n"
                "Используйте /logout для выхода."
            )
            return ConversationHandler.END

        await update.message.reply_text(
            "🔐 Вход в аккаунт\n\n"
            "Введите ваш логин:"
        )

        return LOGIN

    async def process_login_username(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Обработка логина при входе"""
        username = update.message.text.strip()
        context.user_data["login_username"] = username

        await update.message.reply_text(
            f"✅ Введите пароль для пользователя '{username}':"
        )

        return LOGIN_PASSWORD

    async def process_login_password(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Обработка пароля при входе"""
        password = update.message.text.strip()
        username = context.user_data["login_username"]
        user_id = update.effective_user.id

        try:
            data = {
                "login": username,
                "passwordHash": self.hash_password(password)
            }

            result = self.api_request("POST", "/auth/login", data=data)
            token = result["data"]["token"]

            authorized_users[user_id] = token
            del context.user_data["login_username"]

            await update.message.reply_text(
                "✅ Вы успешно вошли в аккаунт!\n\n"
                "Теперь вам доступны все функции управления записями.\n"
                "Используйте /recordings для просмотра ваших аудиозаписей."
            )

            return ConversationHandler.END

        except Exception as e:
            await update.message.reply_text(f"❌ Ошибка входа: {e}\nПопробуйте еще раз:")
            return LOGIN

    async def logout_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Выход из аккаунта"""
        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        if not token:
            await update.message.reply_text("❌ Вы не авторизованы!")
            return

        try:
            self.api_request("POST", "/auth/logout", token=token)
        except Exception as e:
            logger.error(f"Logout API error: {e}")

        authorized_users.pop(user_id, None)

        await update.message.reply_text(
            "✅ Вы вышли из аккаунта!\n\n"
            "Используйте /login для повторного входа."
        )

    # Работа с записями
    async def recordings_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Показ списка записей пользователя"""
        if not await self.check_authorization(update, context):
            return

        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        try:
            result = self.api_request("GET", "/bot/recordings", token=token)
            recordings = result["data"]

            if not recordings:
                await update.message.reply_text(
                    "📼 У вас пока нет аудиозаписей.\n\n"
                    "Загрузите записи через устройство ESP32-C6 используя приложение для записи."
                )
                return

            keyboard = []
            for recording in recordings:
                recording_id = recording["id"]
                filename = recording.get("originalFilename", recording["filename"])
                size_mb = recording["fileSize"] / (1024 * 1024)
                short_name = filename[:25] + "..." if len(filename) > 25 else filename
                button_text = f"📼 {short_name} ({size_mb:.1f} MB)"
                keyboard.append([InlineKeyboardButton(button_text, callback_data=f"recording:{recording_id}")])

            reply_markup = InlineKeyboardMarkup(keyboard)

            await update.message.reply_text(
                f"📼 Ваши аудиозаписи ({len(recordings)}):\n\n"
                "Нажмите на запись для действий:",
                reply_markup=reply_markup
            )

        except Exception as e:
            await update.message.reply_text(f"❌ Ошибка при получении записей: {e}")

    async def recording_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Обработка нажатия на кнопку записи"""
        query = update.callback_query
        await query.answer()

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            result = self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)
            recording = result["data"]

            filename = recording.get("originalFilename", recording["filename"])
            size_mb = recording["fileSize"] / (1024 * 1024)

            keyboard = [
                [
                    InlineKeyboardButton("📥 Скачать", callback_data=f"download:{recording_id}"),
                    InlineKeyboardButton("📝 Переименовать", callback_data=f"rename:{recording_id}")
                ],
                [
                    InlineKeyboardButton("📋 Краткое содержание", callback_data=f"summarize:{recording_id}"),
                    InlineKeyboardButton("📄 Текстовая расшифровка", callback_data=f"transcribe:{recording_id}")
                ],
                [
                    InlineKeyboardButton("❌ Удалить", callback_data=f"delete:{recording_id}"),
                    InlineKeyboardButton("🔙 Назад", callback_data="back_to_recordings")
                ]
            ]

            reply_markup = InlineKeyboardMarkup(keyboard)

            info_text = (
                f"📼 Запись: {filename}\n\n"
                f"📊 Размер: {size_mb:.1f} MB\n"
                f"📅 Создана: {recording.get('createdAt', 'N/A')}\n"
                f"📈 Статус: {recording.get('status', 'N/A')}\n\n"
                "Выберите действие:"
            )

            await query.edit_message_text(text=info_text, reply_markup=reply_markup)

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка: {e}")

    async def download_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Скачивание записи"""
        query = update.callback_query
        await query.answer()

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            result = self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)
            recording = result["data"]
            filename = recording.get("originalFilename", recording["filename"])

            download_url = f"{self.api_base}/bot/recordings/{recording_id}/download"
            headers = {"Authorization": f"Bearer {token}"}

            response = requests.get(download_url, headers=headers, timeout=30)
            response.raise_for_status()

            await query.bot.send_document(
                chat_id=query.message.chat_id,
                document=response.content,
                filename=filename,
                caption=f"📥 Файл: {filename}"
            )

            await query.answer("✅ Файл отправлен!", show_alert=True)

        except Exception as e:
            await query.answer(f"❌ Ошибка скачивания: {e}", show_alert=True)

    async def rename_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Начало переименования записи"""
        query = update.callback_query
        await query.answer()

        recording_id = query.data.split(":")[1]
        context.user_data["rename_recording_id"] = recording_id

        await query.edit_message_text(
            "📝 Введите новое имя файла (без расширения):\n\n"
            "Пример: Моя_аудиозапись_01"
        )

        return RENAME

    async def process_rename(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Обработка нового имени файла"""
        new_name = update.message.text.strip()

        if not new_name:
            await update.message.reply_text("❌ Имя файла не может быть пустым!\nПопробуйте еще раз:")
            return RENAME

        recording_id = context.user_data["rename_recording_id"]
        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        try:
            if not new_name.lower().endswith('.wav'):
                new_name += '.wav'

            data = {"newFilename": new_name}
            result = self.api_request("PUT", f"/bot/recordings/{recording_id}/rename", token=token, data=data)

            del context.user_data["rename_recording_id"]

            await update.message.reply_text(
                f"✅ Запись успешно переименована в: {new_name}\n\n"
                "Используйте /recordings для просмотра обновленного списка."
            )

            return ConversationHandler.END

        except Exception as e:
            await update.message.reply_text(f"❌ Ошибка переименования: {e}\nПопробуйте еще раз:")
            return RENAME

    async def delete_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Удаление записи с подтверждением"""
        query = update.callback_query
        await query.answer()

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            result = self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)
            recording = result["data"]
            filename = recording.get("originalFilename", recording["filename"])

            keyboard = [
                [
                    InlineKeyboardButton("✅ Да, удалить", callback_data=f"confirm_delete:{recording_id}"),
                    InlineKeyboardButton("❌ Нет, отменить", callback_data="cancel_delete")
                ]
            ]

            reply_markup = InlineKeyboardMarkup(keyboard)

            await query.edit_message_text(
                f"⚠️ Вы уверены, что хотите удалить запись?\n\n"
                f"📼 {filename}\n\n"
                "Это действие необратимо!",
                reply_markup=reply_markup
            )

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка: {e}")

    async def confirm_delete_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Подтверждение удаления записи"""
        query = update.callback_query
        await query.answer()

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            result = self.api_request("DELETE", f"/bot/recordings/{recording_id}", token=token)

            await query.edit_message_text(
                "✅ Запись успешно удалена!\n\n"
                "Используйте /recordings для просмотра обновленного списка."
            )

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка удаления: {e}")

    async def cancel_delete_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Отмена удаления записи"""
        query = update.callback_query
        await query.answer()

        await query.edit_message_text("❌ Удаление отменено.\n\nИспользуйте /recordings для возврата к списку.")

    async def summarize_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Запуск суммаризации записи"""
        query = update.callback_query
        await query.answer("🔄 Запускаю суммаризацию...")

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            result = self.api_request("POST", f"/bot/recordings/{recording_id}/summarize", token=token)
            await asyncio.sleep(2)

            recording_result = self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)
            recording = recording_result["data"]

            summary_info = recording.get("summary", {})
            status = summary_info.get("status", "PROCESSING")

            if status == "COMPLETED" and summary_info.get("briefSummary"):
                summary_text = summary_info.get("briefSummary", "")
                await query.edit_message_text(
                    f"📋 Краткое содержание записи:\n\n"
                    f"📄 {summary_text}\n\n"
                    "Используйте /recordings для возврата к списку."
                )
            else:
                await query.edit_message_text(
                    "🔄 Суммаризация в процессе...\n\n"
                    "Это может занять несколько минут.\n"
                    "Попробуйте запросить суммаризацию позже через меню записи."
                )

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка суммаризации: {e}")

    async def transcribe_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Запуск расшифровки записи"""
        query = update.callback_query
        await query.answer("🔄 Запускаю расшифровку...")

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            try:
                transcription_result = self.api_request("GET", f"/bot/recordings/{recording_id}/transcription", token=token)
                transcription = transcription_result["data"]

                if transcription["status"] == "COMPLETED":
                    transcription_text = transcription.get("transcriptionText", "")
                    await query.edit_message_text(
                        f"📄 Текстовая расшифровка записи:\n\n"
                        f"📝 {transcription_text}\n\n"
                        "Используйте /recordings для возврата к списку."
                    )
                    return
                elif transcription["status"] == "FAILED":
                    await query.edit_message_text(
                        f"❌ Расшифровка не удалась:\n{transcription.get('errorMessage', 'Неизвестная ошибка')}\n\n"
                        "Попробуйте запустить расшифровку позже."
                    )
                    return
            except:
                pass

            result = self.api_request("POST", f"/bot/recordings/{recording_id}/transcribe", token=token)
            await asyncio.sleep(3)

            transcription_result = self.api_request("GET", f"/bot/recordings/{recording_id}/transcription", token=token)
            transcription = transcription_result["data"]

            if transcription["status"] == "COMPLETED":
                transcription_text = transcription.get("transcriptionText", "")
                await query.edit_message_text(
                    f"📄 Текстовая расшифровка записи:\n\n"
                    f"📝 {transcription_text}\n\n"
                    "Используйте /recordings для возврата к списку."
                )
            else:
                await query.edit_message_text(
                    "🔄 Расшифровка в процессе...\n\n"
                    "Это может занять несколько минут.\n"
                    "Попробуйте запросить расшифровку позже через меню записи."
                )

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка расшифровки: {e}")

    async def back_to_recordings_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Возврат к списку записей"""
        query = update.callback_query
        await query.answer()

        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        try:
            result = self.api_request("GET", "/bot/recordings", token=token)
            recordings = result["data"]

            if not recordings:
                await query.edit_message_text(
                    "📼 У вас пока нет аудиозаписей.\n\n"
                    "Загрузите записи через устройство ESP32-C6."
                )
                return

            keyboard = []
            for recording in recordings:
                recording_id = recording["id"]
                filename = recording.get("originalFilename", recording["filename"])
                size_mb = recording["fileSize"] / (1024 * 1024)
                short_name = filename[:25] + "..." if len(filename) > 25 else filename
                button_text = f"📼 {short_name} ({size_mb:.1f} MB)"
                keyboard.append([InlineKeyboardButton(button_text, callback_data=f"recording:{recording_id}")])

            reply_markup = InlineKeyboardMarkup(keyboard)

            await query.edit_message_text(
                f"📼 Ваши аудиозаписи ({len(recordings)}):\n\n"
                "Нажмите на запись для действий:",
                reply_markup=reply_markup
            )

        except Exception as e:
            await query.edit_message_text(f"❌ Ошибка при получении записей: {e}")

    async def cancel_conversation(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Отмена текущего диалога"""
        if update.message:
            await update.message.reply_text("❌ Операция отменена.")
        elif update.callback_query:
            await update.callback_query.edit_message_text("❌ Операция отменена.")

        for key in ["register_login", "login_username", "rename_recording_id"]:
            context.user_data.pop(key, None)

        return ConversationHandler.END

    async def unknown_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Обработчик неизвестных команд"""
        await update.message.reply_text(
            "❌ Неизвестная команда!\n\n"
            "Используйте /help для просмотра доступных команд."
        )


# ============================================================================
# ГЛАВНАЯ ФУНКЦИЯ - ВНЕ КЛАССА!
# ============================================================================

def main() -> None:
    """Основная функция запуска бота"""
    if not BOT_TOKEN:
        logger.error("❌ BOT_TOKEN не найден в переменных окружения!")
        logger.error("💡 Создайте файл .env с содержимым: BOT_TOKEN=ваш_токен_бота")
        return

    # Проверка формата токена
    if not re.match(r'^\d+:[A-Za-z0-9_-]+$', BOT_TOKEN):
        logger.error("❌ Неверный формат BOT_TOKEN!")
        logger.error("💡 Токен должен выглядеть как: 123456789:AAHdG7xK...")
        return

    bot = TelegramBot()
    application = Application.builder().token(BOT_TOKEN).build()

    # ConversationHandler для регистрации
    register_conv_handler = ConversationHandler(
        entry_points=[CommandHandler("register", bot.register_command)],
        states={
            REGISTER: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_register_login)],
            REGISTER_PASSWORD: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_register_password)],
        },
        fallbacks=[CommandHandler("cancel", bot.cancel_conversation)],
        per_message=False,  # Явно указываем для подавления предупреждения
    )

    # ConversationHandler для входа
    login_conv_handler = ConversationHandler(
        entry_points=[CommandHandler("login", bot.login_command)],
        states={
            LOGIN: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_login_username)],
            LOGIN_PASSWORD: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_login_password)],
        },
        fallbacks=[CommandHandler("cancel", bot.cancel_conversation)],
        per_message=False,
    )

    # ConversationHandler для переименования
    rename_conv_handler = ConversationHandler(
        entry_points=[CallbackQueryHandler(bot.rename_callback, pattern="^rename:")],
        states={
            RENAME: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_rename)],
        },
        fallbacks=[CommandHandler("cancel", bot.cancel_conversation)],
        per_message=False,
    )

    # Регистрация обработчиков
    application.add_handler(register_conv_handler)
    application.add_handler(login_conv_handler)
    application.add_handler(rename_conv_handler)

    # Обработчики команд
    application.add_handler(CommandHandler("start", bot.start_command))
    application.add_handler(CommandHandler("help", bot.help_command))
    application.add_handler(CommandHandler("logout", bot.logout_command))
    application.add_handler(CommandHandler("recordings", bot.recordings_command))

    # Обработчики callback'ов
    application.add_handler(CallbackQueryHandler(bot.recording_callback, pattern="^recording:"))
    application.add_handler(CallbackQueryHandler(bot.download_callback, pattern="^download:"))
    application.add_handler(CallbackQueryHandler(bot.delete_callback, pattern="^delete:"))
    application.add_handler(CallbackQueryHandler(bot.confirm_delete_callback, pattern="^confirm_delete:"))
    application.add_handler(CallbackQueryHandler(bot.cancel_delete_callback, pattern="^cancel_delete$"))
    application.add_handler(CallbackQueryHandler(bot.summarize_callback, pattern="^summarize:"))
    application.add_handler(CallbackQueryHandler(bot.transcribe_callback, pattern="^transcribe:"))
    application.add_handler(CallbackQueryHandler(bot.back_to_recordings_callback, pattern="^back_to_recordings$"))

    # Обработчик неизвестных команд
    application.add_handler(MessageHandler(filters.COMMAND, bot.unknown_command))

    # Запуск бота
    logger.info("🚀 Запуск Telegram бота...")
    logger.info(f"📡 Сервер: {SERVER_URL}")
    application.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()