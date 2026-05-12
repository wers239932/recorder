from __future__ import annotations

import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, status
from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AsyncOpenAI,
    RateLimitError,
)
from pydantic import BaseModel, Field

load_dotenv()

logger = logging.getLogger(__name__)

DEFAULT_BASE_URL = "https://api.vsellm.ru/v1"
LLM_MODEL = "z-ai/glm-4.6v-flash"
SYSTEM_PROMPT = "Сделай краткую, но информативную выжимку этого текста."


class SummarizeRequest(BaseModel):
    text: str = Field(..., min_length=1, description="Текст для суммаризации")


class SummarizeResponse(BaseModel):
    summary: str


class HealthResponse(BaseModel):
    status: str


def _get_openai_client() -> AsyncOpenAI:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is not set")
    base_url = os.getenv("OPENAI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    return AsyncOpenAI(api_key=api_key, base_url=base_url)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    try:
        app.state.openai_client = _get_openai_client()
    except RuntimeError as e:
        logger.error("%s", e)
        app.state.openai_client = None
    yield
    client: AsyncOpenAI | None = getattr(app.state, "openai_client", None)
    if client is not None:
        await client.close()


app = FastAPI(title="Summarizer", lifespan=lifespan)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/summarize", response_model=SummarizeResponse)
async def summarize(body: SummarizeRequest) -> SummarizeResponse:
    client: AsyncOpenAI | None = getattr(app.state, "openai_client", None)
    if client is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM client is not configured (missing OPENAI_API_KEY)",
        )

    try:
        response = await client.chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": body.text},
            ],
        )
    except RateLimitError as e:
        logger.warning("LLM rate limit: %s", e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM API rate limit exceeded",
        ) from e
    except APITimeoutError as e:
        logger.warning("LLM timeout: %s", e)
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="LLM API request timed out",
        ) from e
    except APIConnectionError as e:
        logger.warning("LLM connection error: %s", e)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Could not connect to LLM API",
        ) from e
    except APIStatusError as e:
        logger.warning("LLM API error: %s", e)
        code = e.status_code or 502
        if code >= 500:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="LLM API returned an error",
            ) from e
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LLM API request failed",
        ) from e
    except Exception as e:
        logger.exception("Unexpected error calling LLM")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Unexpected error while summarizing",
        ) from e

    choice = response.choices[0] if response.choices else None
    content: str | None = choice.message.content if choice and choice.message else None
    if not content:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LLM returned empty summary",
        )

    return SummarizeResponse(summary=content)


logging.basicConfig(level=logging.INFO)
