"""ASR transcription API: faster-whisper on CPU."""

from __future__ import annotations

import asyncio
import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI, Request, UploadFile

from faster_whisper import WhisperModel


# Настройка зеркала HuggingFace для обхода прокси
os.environ["HF_ENDPOINT"] = os.getenv("HF_ENDPOINT", "https://hf-mirror.com")


def _suffix_from_upload(filename: str | None) -> str:
    if not filename:
        return ".tmp"
    suffix = Path(filename).suffix
    return suffix if suffix else ".tmp"


def _transcribe_to_text(model: WhisperModel, audio_path: str) -> str:
    segments, _info = model.transcribe(audio_path)
    return "".join(segment.text for segment in segments).strip()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.model = WhisperModel("small", device="cpu", compute_type="int8")
    yield


app = FastAPI(title="ASR Transcription Service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/transcribe")
async def transcribe(request: Request, file: UploadFile) -> dict[str, str]:
    model: WhisperModel = request.app.state.model
    suffix = _suffix_from_upload(file.filename)
    tmp_path: str | None = None
    try:
        content = await file.read()
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            tmp.write(content)

        assert tmp_path is not None
        text = await asyncio.to_thread(_transcribe_to_text, model, tmp_path)
        return {"text": text}
    finally:
        if tmp_path is not None:
            Path(tmp_path).unlink(missing_ok=True)
