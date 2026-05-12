"""Send a local audio file to the transcription service."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import requests


def main() -> None:
    parser = argparse.ArgumentParser(description="POST a file to POST /transcribe")
    parser.add_argument(
        "file",
        type=Path,
        help="Path to an audio file (wav, mp3, etc.)",
    )
    parser.add_argument(
        "--base-url",
        default="http://127.0.0.1:8000",
        help="Service base URL (default: %(default)s)",
    )
    args = parser.parse_args()
    path: Path = args.file
    if not path.is_file():
        print(f"Not a file: {path}", file=sys.stderr)
        sys.exit(1)

    url = f"{args.base_url.rstrip('/')}/transcribe"
    with path.open("rb") as f:
        response = requests.post(url, files={"file": (path.name, f)}, timeout=600)

    try:
        response.raise_for_status()
    except requests.HTTPError as e:
        print(f"HTTP {response.status_code}: {response.text}", file=sys.stderr)
        raise SystemExit(1) from e

    data = response.json()
    print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
