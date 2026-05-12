import json
import os

import requests

BASE_URL = os.getenv("SUMMARIZER_URL", "http://127.0.0.1:8000").rstrip("/")

SAMPLE_TEXT = """
Какой-то текст
"""


def main() -> None:
    url = f"{BASE_URL}/summarize"
    response = requests.post(
        url,
        json={"text": SAMPLE_TEXT.strip()},
        timeout=120,
        headers={"Content-Type": "application/json"},
    )
    print(f"HTTP {response.status_code}")
    try:
        data = response.json()
        print(json.dumps(data, ensure_ascii=False, indent=2))
    except requests.JSONDecodeError:
        print(response.text)


if __name__ == "__main__":
    main()
