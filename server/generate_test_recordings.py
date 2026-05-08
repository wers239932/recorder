#!/usr/bin/env python3
"""
Скрипт для создания тестовых WAV-записей для пользователя gay.
Генерирует пустые WAV-файлы и загружает их через API.
"""

import wave
import struct
import requests
import random
from datetime import datetime, timedelta

SERVER_URL = "http://localhost:8080"
API_BASE = f"{SERVER_URL}/api/v1"
LOGIN = "gay"
PASSWORD_HASH = "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92"

def generate_wav_file(filename: str, duration_seconds: int = 5, sample_rate: int = 16000):
    """Генерирует простой WAV-файл с синусоидальным сигналом."""
    import math
    num_samples = duration_seconds * sample_rate
    
    with wave.open(filename, 'w') as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit
        wav_file.setframerate(sample_rate)
        
        frequency = 440 + random.randint(-50, 50)  # 440 Hz ± 50
        
        for i in range(num_samples):
            # Генерируем синусоиду
            value = int(32767 * 0.5 * math.sin(2 * math.pi * frequency * i / sample_rate))
            wav_file.writeframes(struct.pack('<h', value))
    
    return filename

def login() -> str:
    """Вход и получение токена."""
    response = requests.post(
        f"{API_BASE}/auth/login",
        json={"login": LOGIN, "passwordHash": PASSWORD_HASH},
        timeout=30
    )
    response.raise_for_status()
    return response.json()["data"]["token"]

def upload_recording(token: str, wav_path: str, device_info: str = "Test-Generator"):
    """Загрузка WAV-файла на сервер."""
    upload_url = f"{API_BASE}/bot/recordings/upload"
    headers = {"Authorization": f"Bearer {token}"}
    
    with open(wav_path, 'rb') as f:
        files = {'file': (wav_path.split('/')[-1], f, 'audio/wav')}
        data = {'deviceInfo': device_info}
        
        response = requests.post(upload_url, headers=headers, files=files, data=data, timeout=60)
        print(f"   Response status: {response.status_code}")
        print(f"   Response body: {response.text[:500]}")
        response.raise_for_status()
    
    return response.json()

def main():
    print("🔐 Вход в систему...")
    token = login()
    print(f"✅ Токен получен: {token[:16]}...")
    
    # Генерируем 5 тестовых записей
    recordings_data = [
        {"name": "meeting_2026-05-01.wav", "duration": 10, "device": "ESP32-C6-Office"},
        {"name": "interview_2026-05-03.wav", "duration": 15, "device": "ESP32-C6-Studio"},
        {"name": "lecture_2026-05-05.wav", "duration": 20, "device": "ESP32-C6-University"},
        {"name": "call_2026-05-07.wav", "duration": 8, "device": "ESP32-C6-Home"},
        {"name": "notes_2026-05-08.wav", "duration": 5, "device": "ESP32-C6-Mobile"},
    ]
    
    print(f"\n📼 Генерация {len(recordings_data)} тестовых записей...")
    
    for i, rec in enumerate(recordings_data, 1):
        wav_path = f"/tmp/{rec['name']}"
        print(f"\n[{i}/{len(recordings_data)}] Генерация: {rec['name']} ({rec['duration']} сек)")
        
        # Генерируем WAV-файл
        generate_wav_file(wav_path, duration_seconds=rec['duration'])
        
        # Загружаем на сервер
        try:
            result = upload_recording(token, wav_path, device_info=rec['device'])
            print(f"   ✅ Загружено: {rec['name']}")
        except Exception as e:
            print(f"   ❌ Ошибка загрузки: {e}")
    
    print("\n🎉 Готово! Проверьте записи через /recordings в Telegram-боте.")

if __name__ == "__main__":
    main()
