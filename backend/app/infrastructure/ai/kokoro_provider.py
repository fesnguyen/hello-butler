from __future__ import annotations

import asyncio
import importlib
import io
import logging
import time
import uuid
from typing import Any, cast

from app.application.butler import ButlerAIUnavailableError, ButlerSpeech

logger = logging.getLogger(__name__)


class KokoroButlerVoiceProvider:
    def __init__(self, *, language: str = "a", voice: str = "af_heart") -> None:
        self._language = language
        self._voice = voice
        self._pipeline: Any | None = None
        self._lock = asyncio.Lock()  # Kokoro model inference is serialized per process.

    async def synthesize(self, *, text: str, request_id: uuid.UUID | None = None) -> ButlerSpeech:
        started = time.perf_counter()
        logger.info(
            "Kokoro TTS started request_id=%s voice=%s text_chars=%d",
            request_id,
            self._voice,
            len(text),
        )
        try:
            async with self._lock:
                audio = await asyncio.to_thread(self._render_wav, text)
        except Exception as exc:
            logger.warning(
                "Kokoro TTS failed request_id=%s elapsed_ms=%d error_type=%s",
                request_id,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Kokoro TTS request failed") from exc
        logger.info(
            "Kokoro TTS completed request_id=%s elapsed_ms=%d wav_bytes=%d",
            request_id,
            round((time.perf_counter() - started) * 1000),
            len(audio),
        )
        return ButlerSpeech(audio=audio, mime_type="audio/wav")

    def _render_wav(self, text: str) -> bytes:
        np = importlib.import_module("numpy")
        sf = importlib.import_module("soundfile")
        pipeline_type = importlib.import_module("kokoro").KPipeline

        if self._pipeline is None:  # Model loading/downloading is deferred until first use.
            self._pipeline = pipeline_type(lang_code=self._language)
        pipeline = cast(Any, self._pipeline)
        chunks = [np.asarray(audio) for _, _, audio in pipeline(text, voice=self._voice)]
        if not chunks:
            raise ValueError("Kokoro returned no audio")
        output = io.BytesIO()
        sf.write(output, np.concatenate(chunks), 24_000, format="WAV", subtype="PCM_16")
        return output.getvalue()
