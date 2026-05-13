#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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

    # API методы с улучшенной обработкой ошибок
    def api_request(self, method: str, endpoint: str, token: str = None, data: dict = None, params: dict = None) -> dict:
        """Универсальный метод для API запросов с подробной обработкой ошибок"""
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

            # Обработка HTTP статусов
            if response.status_code == 401:
                # Для login/register endpoint 401 означает неверные данные
                if endpoint in ["/auth/login", "/auth/register"]:
                    raise APIError("auth_failed", "Неверный логин или пароль")
                else:
                    raise APIError("session_expired", "Сессия истекла. Пожалуйста, войдите снова.")
            elif response.status_code == 403:
                raise APIError("access_denied", "Доступ запрещён. Убедитесь, что вы авторизованы.")
            elif response.status_code == 404:
                raise APIError("not_found", "Ресурс не найден.")
            elif response.status_code == 400:
                # Пытаемся получить сообщение об ошибке из ответа
                try:
                    error_data = response.json()
                    error_message = error_data.get("error", {}).get("message", "Неверный запрос.")
                except:
                    error_message = "Неверный запрос."
                raise APIError("bad_request", error_message)
            elif response.status_code == 500:
                raise APIError("server_error", "Внутренняя ошибка сервера. Попробуйте позже.")
            elif response.status_code == 502:
                raise APIError("gateway_error", "Сервер временно недоступен. Попробуйте позже.")
            elif response.status_code == 503:
                raise APIError("service_unavailable", "Сервис временно недоступен. Попробуйте позже.")
            elif response.status_code >= 400:
                raise APIError("http_error", f"Ошибка сервера: {response.status_code}")

            response.raise_for_status()
            return response.json()

        except requests.exceptions.ConnectionError as e:
            logger.error(f"Connection error: {e}")
            raise APIError("connection_error", "Не удалось подключиться к серверу. Проверьте, что сервер запущен.")
        except requests.exceptions.Timeout as e:
            logger.error(f"Timeout error: {e}")
            raise APIError("timeout", "Превышено время ожидания ответа от сервера. Попробуйте позже.")
        except requests.exceptions.RequestException as e:
            logger.error(f"Request exception: {e}")
            raise APIError("network_error", f"Ошибка сети: {str(e)}")

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
            "📱 Устройства (требуется авторизация):\n"
            "/devices - список привязанных устройств\n\n"
            "💡 Советы:\n"
            "• Все операции с записями выполняются только для ваших файлов\n"
            "• Устройство автоматически привязывается при первой аутентификации\n"
            "• Используйте те же логин и пароль на устройстве и в боте\n"
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
                "username": user.username or "",
                "login": login,
                "passwordHash": self.hash_password(password),
                "firstName": user.first_name or "",
                "lastName": user.last_name or ""
            }

            result = self.api_request("POST", "/auth/register", data=data)
            del context.user_data["register_login"]

            await update.message.reply_text(
                f"✅ Регистрация успешна!\n\n"
                f"Ваш логин: {login}\n"
                f"Теперь вы можете войти в аккаунт командой /login"
            )

            return ConversationHandler.END

        except APIError as e:
            # Обработка известных ошибок API
            if e.error_type == "bad_request":
                if "логином" in e.message.lower():
                    await update.message.reply_text(
                        "❌ Такой логин уже занят!\n"
                        "Попробуйте другой логин:"
                    )
                    return REGISTER
                elif "telegram" in e.message.lower():
                    await update.message.reply_text(
                        "❌ Этот Telegram-аккаунт уже зарегистрирован!\n\n"
                        "Используйте /login для входа в существующий аккаунт."
                    )
                    return ConversationHandler.END
                else:
                    await update.message.reply_text(f"❌ Ошибка регистрации: {e.message}\nПопробуйте другой логин:")
                    return ConversationHandler.END
            elif e.error_type == "connection_error":
                await update.message.reply_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
                return ConversationHandler.END
            else:
                await update.message.reply_text(f"❌ Ошибка регистрации: {e.message}")
                return ConversationHandler.END

        except Exception as e:
            logger.error(f"Unexpected registration error: {e}")
            await update.message.reply_text(
                "❌ Произошла непредвиденная ошибка.\n"
                "Пожалуйста, попробуйте позже."
            )
            return ConversationHandler.END

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

        except APIError as e:
            if e.error_type == "auth_failed" or e.error_type == "bad_request":
                await update.message.reply_text(
                    "❌ Неверный логин или пароль!\n"
                    "Попробуйте еще раз:"
                )
                return LOGIN
            elif e.error_type == "connection_error":
                await update.message.reply_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
                return ConversationHandler.END
            else:
                await update.message.reply_text(f"❌ Ошибка входа: {e.message}")
                return LOGIN

        except Exception as e:
            logger.error(f"Unexpected login error: {e}")
            await update.message.reply_text(
                "❌ Произошла непредвиденная ошибка.\n"
                "Пожалуйста, попробуйте позже."
            )
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
        except APIError as e:
            logger.warning(f"Logout API error (ignored): {e.message}")
        except Exception as e:
            logger.error(f"Logout error: {e}")

        authorized_users.pop(user_id, None)

        await update.message.reply_text(
            "✅ Вы вышли из аккаунта!\n\n"
            "Используйте /login для повторного входа."
        )

    async def devices_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Список привязанных устройств пользователя"""
        if not await self.check_authorization(update, context):
            return

        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        try:
            result = self.api_request("GET", "/bot/devices", token=token)
            devices = result["data"]

            if not devices:
                await update.message.reply_text(
                    "📱 У вас пока нет привязанных устройств.\n\n"
                    "Устройство автоматически привязывается при первой аутентификации.\n\n"
                    "Используйте те же логин и пароль, что и при входе в бота."
                )
                return

            keyboard = []
            for device_login in devices:
                button_text = f"📼 {device_login}"
                keyboard.append([InlineKeyboardButton(button_text, callback_data=f"device:{device_login}")])

            reply_markup = InlineKeyboardMarkup(keyboard)

            await update.message.reply_text(
                f"📱 Ваши устройства ({len(devices)}):\n\n"
                "Устройство автоматически привязано при первой аутентификации.\n\n"
                "Нажмите на устройство для управления:",
                reply_markup=reply_markup
            )

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(user_id, None)
                await update.message.reply_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "connection_error":
                await update.message.reply_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
            else:
                await update.message.reply_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Unexpected devices error: {e}")
            await update.message.reply_text(
                "❌ Произошла непредвиденная ошибка.\n"
                "Пожалуйста, попробуйте позже."
            )

    async def device_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Обработка нажатия на устройство"""
        query = update.callback_query
        await query.answer()

        try:
            device_login = query.data.split(":")[1]

            keyboard = [
                [InlineKeyboardButton("🔙 Назад", callback_data="back_to_devices")]
            ]
            reply_markup = InlineKeyboardMarkup(keyboard)

            await query.edit_message_text(
                f"📼 Устройство: {device_login}\n\n"
                "Это устройство автоматически привязано к вашему аккаунту.\n"
                "Используйте те же логин и пароль для аутентификации.",
                reply_markup=reply_markup
            )

        except Exception as e:
            logger.error(f"Device callback error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
            )

    async def back_to_devices_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Возврат к списку устройств"""
        query = update.callback_query
        await query.answer()

        # Перезапускаем команду /devices, но используем query.message для ответа
        user_id = update.effective_user.id
        token = self.get_user_token(user_id)

        try:
            result = self.api_request("GET", "/bot/devices", token=token)
            devices = result["data"]

            if not devices:
                await query.edit_message_text(
                    "📱 У вас пока нет привязанных устройств.\n\n"
                    "Устройство автоматически привязывается при первой аутентификации.\n\n"
                    "Используйте те же логин и пароль, что и при входе в бота."
                )
                return

            keyboard = []
            for device_login in devices:
                button_text = f"📼 {device_login}"
                keyboard.append([InlineKeyboardButton(button_text, callback_data=f"device:{device_login}")])

            reply_markup = InlineKeyboardMarkup(keyboard)

            await query.edit_message_text(
                f"📱 Ваши устройства ({len(devices)}):\n\n"
                "Устройство автоматически привязано при первой аутентификации.\n\n"
                "Нажмите на устройство для управления:",
                reply_markup=reply_markup
            )

        except Exception as e:
            logger.error(f"Back to devices error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
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

        except APIError as e:
            if e.error_type == "session_expired":
                # Очищаем сессию и просим войти снова
                authorized_users.pop(user_id, None)
                await update.message.reply_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "connection_error":
                await update.message.reply_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
            else:
                await update.message.reply_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Unexpected recordings error: {e}")
            await update.message.reply_text(
                "❌ Произошла непредвиденная ошибка.\n"
                "Пожалуйста, попробуйте позже."
            )

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

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена.\n"
                    "Возможно, она была удалена."
                )
            elif e.error_type == "connection_error":
                await query.edit_message_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Unexpected recording callback error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка.\n"
                "Пожалуйста, попробуйте позже."
            )

    async def download_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Скачивание записи"""
        query = update.callback_query
        await query.answer("🔄 Загружаю файл...")

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            logger.info(f"Download request: recording_id={recording_id}, user_id={user_id}")

            # Получаем метаданные записи
            result = self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)
            recording = result["data"]
            filename = recording.get("originalFilename", recording["filename"])

            logger.info(f"Recording metadata: filename={filename}")

            # Скачиваем файл
            download_url = f"{self.api_base}/bot/recordings/{recording_id}/download"
            headers = {"Authorization": f"Bearer {token}"}

            logger.info(f"Downloading from: {download_url}")

            response = requests.get(download_url, headers=headers, timeout=60, stream=True)

            logger.info(f"Download response status: {response.status_code}")

            if response.status_code == 401:
                # Токен истёк — пробуем обновить
                logger.warning(f"Token expired for user {user_id}, attempting to refresh...")
                authorized_users.pop(user_id, None)
                
                # Отправляем сообщение с просьбой войти заново
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login\n"
                    "После входа попробуйте скачать файл ещё раз."
                )
                return
            elif response.status_code == 404:
                logger.error(f"File not found: {recording_id}")
                await query.edit_message_text("❌ Файл не найден.\nВозможно, он был удалён.")
                return
            elif response.status_code >= 400:
                logger.error(f"Download error: {response.status_code} - {response.text}")
                await query.edit_message_text(f"❌ Ошибка скачивания: {response.status_code}")
                return

            from io import BytesIO
            file_buffer = BytesIO(response.content)
            file_buffer.name = filename

            logger.info(f"Sending file: {filename}, size={len(response.content)} bytes")

            await context.bot.send_document(
                chat_id=query.message.chat_id,
                document=file_buffer,
                filename=filename,
                caption=f"📼 {filename}\n\nФайл отправлен!",
                read_timeout=60,
                write_timeout=60
            )

            await query.edit_message_text("✅ Файл отправлен!\nПроверьте вложения выше.")
            logger.info(f"File sent successfully: {filename}")

        except requests.exceptions.Timeout:
            logger.error(f"Download timeout for recording {recording_id}")
            await query.edit_message_text(
                "⏱️ Превышено время ожидания.\n"
                "Файл слишком большой или соединение медленное.\n"
                "Попробуйте позже."
            )
        except APIError as e:
            logger.error(f"API error during download: {e.error_type} - {e.message}")
            if e.error_type == "session_expired":
                authorized_users.pop(user_id, None)
                await query.edit_message_text(
                    "⏰ Сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            else:
                await query.edit_message_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Download error: {e}", exc_info=True)
            await query.edit_message_text(
                "❌ Ошибка при скачивании.\n"
                "Попробуйте позже или обратитесь к разработчику."
            )

    async def rename_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
        """Начало переименования записи"""
        query = update.callback_query
        await query.answer()

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            # Проверяем, что запись существует
            self.api_request("GET", f"/bot/recordings/{recording_id}", token=token)

            context.user_data["rename_recording_id"] = recording_id

            await query.edit_message_text(
                "📝 Введите новое имя файла (без расширения):\n\n"
                "Пример: Моя_аудиозапись_01"
            )

            return RENAME

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена.\n"
                    "Возможно, она была удалена."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка: {e.message}")
            return ConversationHandler.END
        except Exception as e:
            logger.error(f"Rename callback error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
            )
            return ConversationHandler.END

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

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(user_id, None)
                await update.message.reply_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
                return ConversationHandler.END
            elif e.error_type == "not_found":
                await update.message.reply_text(
                    "❌ Запись не найдена.\n"
                    "Возможно, она была удалена."
                )
                return ConversationHandler.END
            elif e.error_type == "bad_request":
                await update.message.reply_text(
                    f"❌ Ошибка переименования: {e.message}\n"
                    "Попробуйте другое имя:"
                )
                return RENAME
            else:
                await update.message.reply_text(f"❌ Ошибка переименования: {e.message}")
                return ConversationHandler.END

        except Exception as e:
            logger.error(f"Rename error: {e}")
            await update.message.reply_text(
                "❌ Произошла непредвиденная ошибка."
            )
            return ConversationHandler.END

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

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена.\n"
                    "Возможно, она была удалена."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Delete callback error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
            )

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

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена.\n"
                    "Возможно, она уже была удалена."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка удаления: {e.message}")
        except Exception as e:
            logger.error(f"Delete error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
            )

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

            # Проверяем существующую суммаризацию через отдельный endpoint
            try:
                summary_result = self.api_request("GET", f"/bot/recordings/{recording_id}/summary", token=token)
                summary_data = summary_result.get("data", {})
                status = summary_data.get("status", "PROCESSING")
                summary_text = summary_data.get("summaryText", "")
            except APIError as e:
                if e.error_type == "not_found":
                    status = "NOT_STARTED"
                    summary_text = ""
                else:
                    raise

            if status == "COMPLETED" and summary_text:
                await query.edit_message_text(
                    f"📋 Краткое содержание записи:\n\n"
                    f"📄 {summary_text}\n\n"
                    "Используйте /recordings для возврата к списку."
                )
                return
            elif status == "FAILED":
                await query.edit_message_text(
                    "❌ Суммаризация не удалась.\n\n"
                    "Попробуйте запустить суммаризацию позже."
                )
                return
            elif status in ("PROCESSING", "SUMMARIZING"):
                await query.edit_message_text(
                    "🔄 Суммаризация уже выполняется...\n\n"
                    "Это может занять несколько минут.\n"
                    "Попробуйте запросить суммаризацию позже через меню записи."
                )
                return

            # Запускаем суммаризацию
            result = self.api_request("POST", f"/bot/recordings/{recording_id}/summarize", token=token)
            await asyncio.sleep(2)

            # Получаем обновлённый статус
            try:
                summary_result = self.api_request("GET", f"/bot/recordings/{recording_id}/summary", token=token)
                summary_data = summary_result.get("data", {})
                status = summary_data.get("status", "PROCESSING")
                summary_text = summary_data.get("summaryText", "")
            except APIError as e:
                if e.error_type == "not_found":
                    status = "PROCESSING"
                    summary_text = ""
                else:
                    raise

            if status == "COMPLETED" and summary_text:
                await query.edit_message_text(
                    f"📋 Краткое содержание записи:\n\n"
                    f"📄 {summary_text}\n\n"
                    "Используйте /recordings для возврата к списку."
                )
            else:
                await query.edit_message_text(
                    "🔄 Суммаризация запущена и выполняется...\n\n"
                    "Это может занять несколько минут.\n"
                    "Попробуйте запросить суммаризацию позже через меню записи."
                )

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена."
                )
            elif e.error_type == "service_unavailable":
                await query.edit_message_text(
                    "🔄 Сервис суммаризации временно недоступен.\n"
                    "Попробуйте позже."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка суммаризации: {e.message}")
        except Exception as e:
            logger.error(f"Summarization error: {e}")
            await query.edit_message_text(
                "❌ Произошла ошибка при суммаризации.\n"
                "Попробуйте позже."
            )

    async def transcribe_callback(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        """Запуск расшифровки записи"""
        query = update.callback_query
        await query.answer("🔄 Запускаю расшифровку...")

        try:
            recording_id = query.data.split(":")[1]
            user_id = update.effective_user.id
            token = self.get_user_token(user_id)

            # Проверяем существующую расшифровку
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
                elif transcription["status"] == "PROCESSING":
                    await query.edit_message_text(
                        "🔄 Расшифровка уже выполняется...\n\n"
                        "Это может занять несколько минут.\n"
                        "Попробуйте запросить расшифровку позже через меню записи."
                    )
                    return
            except APIError as e:
                if e.error_type != "not_found":
                    raise
                # Расшифровка не найдена, нужно запустить
                pass

            # Запускаем расшифровку
            result = self.api_request("POST", f"/bot/recordings/{recording_id}/transcribe", token=token)
            await asyncio.sleep(3)

            # Получаем результат
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
                    "🔄 Расшифровка запущена и выполняется...\n\n"
                    "Это может занять несколько минут.\n"
                    "Попробуйте запросить расшифровку позже через меню записи."
                )

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(update.effective_user.id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "not_found":
                await query.edit_message_text(
                    "❌ Запись не найдена."
                )
            elif e.error_type == "service_unavailable":
                await query.edit_message_text(
                    "🔄 Сервис расшифровки временно недоступен.\n"
                    "Попробуйте позже."
                )
            elif e.error_type == "server_error":
                await query.edit_message_text(
                    "❌ Внутренняя ошибка сервера.\n"
                    "Попробуйте позже или обратитесь к разработчику."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка расшифровки: {e.message}")
        except Exception as e:
            logger.error(f"Transcription error: {e}")
            await query.edit_message_text(
                "❌ Произошла ошибка при расшифровке.\n"
                "Попробуйте позже."
            )

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

        except APIError as e:
            if e.error_type == "session_expired":
                authorized_users.pop(user_id, None)
                await query.edit_message_text(
                    "⏰ Ваша сессия истекла.\n\n"
                    "Пожалуйста, войдите снова командой /login"
                )
            elif e.error_type == "connection_error":
                await query.edit_message_text(
                    "❌ Сервер временно недоступен.\n"
                    "Пожалуйста, попробуйте позже."
                )
            else:
                await query.edit_message_text(f"❌ Ошибка: {e.message}")
        except Exception as e:
            logger.error(f"Back to recordings error: {e}")
            await query.edit_message_text(
                "❌ Произошла непредвиденная ошибка."
            )

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
        command = update.message.text.split()[0] if update.message.text else "/unknown"

        # Игнорируем известные команды, которые уже обработаны
        known_commands = ["/start", "/help", "/logout", "/recordings", "/register", "/login", "/cancel", "/devices"]
        if command.lower() in known_commands:
            return

        await update.message.reply_text(
            "❌ Неизвестная команда!\n\n"
            "Используйте /help для просмотра доступных команд."
        )


# ============================================================================
# КЛАССЫ ИСКЛЮЧЕНИЙ
# ============================================================================

class APIError(Exception):
    """Пользовательское исключение для ошибок API"""
    def __init__(self, error_type: str, message: str):
        self.error_type = error_type
        self.message = message
        super().__init__(message)


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
        fallbacks=[
            CommandHandler("cancel", bot.cancel_conversation),
            CommandHandler("login", bot.login_command),
            CommandHandler("logout", bot.logout_command),
            CommandHandler("recordings", bot.recordings_command),
            CommandHandler("start", bot.start_command),
            CommandHandler("help", bot.help_command),
        ],
        per_message=False,
        allow_reentry=True,
    )

    # ConversationHandler для входа
    login_conv_handler = ConversationHandler(
        entry_points=[CommandHandler("login", bot.login_command)],
        states={
            LOGIN: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_login_username)],
            LOGIN_PASSWORD: [MessageHandler(filters.TEXT & ~filters.COMMAND, bot.process_login_password)],
        },
        fallbacks=[
            CommandHandler("cancel", bot.cancel_conversation),
            CommandHandler("register", bot.register_command),
            CommandHandler("logout", bot.logout_command),
            CommandHandler("recordings", bot.recordings_command),
            CommandHandler("start", bot.start_command),
            CommandHandler("help", bot.help_command),
        ],
        per_message=False,
        allow_reentry=True,
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

    # Регистрация обработчиков - ВАЖНО: сначала конкретные, потом общие
    application.add_handler(register_conv_handler)
    application.add_handler(login_conv_handler)
    application.add_handler(rename_conv_handler)

    # Обработчики команд
    application.add_handler(CommandHandler("start", bot.start_command))
    application.add_handler(CommandHandler("help", bot.help_command))
    application.add_handler(CommandHandler("logout", bot.logout_command))
    application.add_handler(CommandHandler("recordings", bot.recordings_command))
    application.add_handler(CommandHandler("devices", bot.devices_command))

    # Обработчики callback'ов
    application.add_handler(CallbackQueryHandler(bot.recording_callback, pattern="^recording:"))
    application.add_handler(CallbackQueryHandler(bot.download_callback, pattern="^download:"))
    application.add_handler(CallbackQueryHandler(bot.delete_callback, pattern="^delete:"))
    application.add_handler(CallbackQueryHandler(bot.confirm_delete_callback, pattern="^confirm_delete:"))
    application.add_handler(CallbackQueryHandler(bot.cancel_delete_callback, pattern="^cancel_delete$"))
    application.add_handler(CallbackQueryHandler(bot.summarize_callback, pattern="^summarize:"))
    application.add_handler(CallbackQueryHandler(bot.transcribe_callback, pattern="^transcribe:"))
    application.add_handler(CallbackQueryHandler(bot.back_to_recordings_callback, pattern="^back_to_recordings$"))
    application.add_handler(CallbackQueryHandler(bot.device_callback, pattern="^device:"))
    application.add_handler(CallbackQueryHandler(bot.back_to_devices_callback, pattern="^back_to_devices$"))

    # Обработчик неизвестных команд - ДОЛЖЕН БЫТЬ ПОСЛЕДНИМ
    application.add_handler(MessageHandler(filters.COMMAND, bot.unknown_command))

    # Запуск бота
    logger.info("🚀 Запуск Telegram бота...")
    logger.info(f"📡 Сервер: {SERVER_URL}")
    application.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()
