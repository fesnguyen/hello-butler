from __future__ import annotations

from pathlib import Path
from typing import Protocol

from openai import APIError, AsyncOpenAI

from app.application.butler.contracts import ButlerAIUnavailableError


class ButlerAudioProvider(Protocol):
    async def transcribe(self, path: Path) -> str: ...
    async def synthesize(self, text: str, path: Path) -> str: ...


class OpenAIButlerAudioProvider:
    def __init__(self, *, api_key: str, transcription_model: str, tts_model: str, voice: str):
        self._client = AsyncOpenAI(api_key=api_key) if api_key else None
        self._transcription_model = transcription_model
        self._tts_model = tts_model
        self._voice = voice

    async def transcribe(self, path: Path) -> str:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        try:
            with path.open("rb") as audio:
                result = await self._client.audio.transcriptions.create(
                    model=self._transcription_model, file=audio
                )
        except (APIError, OSError) as exc:
            raise ButlerAIUnavailableError("Audio transcription failed") from exc
        text = result.text.strip()
        if not text:
            raise ButlerAIUnavailableError("Audio transcription was empty")
        return text

    async def synthesize(self, text: str, path: Path) -> str:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            response = await self._client.audio.speech.create(
                model=self._tts_model,
                voice=self._voice,
                input=text,
                response_format="mp3",
            )
            path.write_bytes(response.content)
        except (APIError, OSError) as exc:
            path.unlink(missing_ok=True)
            raise ButlerAIUnavailableError("Response audio generation failed") from exc
        return "audio/mpeg"
