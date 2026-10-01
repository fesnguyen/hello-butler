from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
import uuid
from pathlib import Path
from typing import Any, TypeVar, cast

from openai import APIError, AsyncOpenAI
from pydantic import BaseModel, ValidationError

from app.application.butler import (
    ButlerAIUnavailableError,
    ButlerContext,
    ButlerInteractionProposal,
    ButlerSpeech,
)
from app.application.planning.contracts import (
    DayPlanningInput,
    GoodNightSummaryDraft,
    GoodNightSummaryInput,
    MorningBriefDraft,
    PlannedDayProposal,
)

from .prompts import (
    BUTLER_INTERACTION_INSTRUCTIONS,
    DAY_PLANNING_INSTRUCTIONS,
    GOOD_NIGHT_SUMMARY_INSTRUCTIONS,
    MORNING_BRIEF_INSTRUCTIONS,
)

logger = logging.getLogger(__name__)
ModelT = TypeVar("ModelT", bound=BaseModel)
_INTERACTION_TOOL = "submit_butler_interaction"


class OpenAIButlerVoiceProvider:
    def __init__(
        self, *, api_key: str, model: str = "gpt-4o-mini-tts", voice: str = "alloy"
    ) -> None:
        self._model = model
        self._voice = voice
        self._client = AsyncOpenAI(api_key=api_key) if api_key else None

    async def synthesize(self, *, text: str, request_id: uuid.UUID | None = None) -> ButlerSpeech:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured for TTS")
        started = time.perf_counter()
        logger.info(
            "Butler TTS started request_id=%s model=%s text_chars=%d",
            request_id,
            self._model,
            len(text),
        )
        try:
            response = await self._client.audio.speech.create(
                model=self._model,
                voice=cast(Any, self._voice),
                input=text,
                response_format="wav",
            )
            audio = response.content
            if len(audio) < 12 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
                raise ButlerAIUnavailableError("TTS returned invalid WAV audio")
        except (APIError, OSError) as exc:
            logger.warning(
                "Butler TTS failed request_id=%s model=%s elapsed_ms=%d error_type=%s",
                request_id,
                self._model,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("TTS provider request failed") from exc
        logger.info(
            "Butler TTS completed request_id=%s model=%s elapsed_ms=%d wav_bytes=%d",
            request_id,
            self._model,
            round((time.perf_counter() - started) * 1000),
            len(audio),
        )
        return ButlerSpeech(audio=audio, mime_type="audio/wav")


class OpenAIButlerProvider:
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        audio_model: str = "gpt-audio-1.5",
    ) -> None:
        self._model = model
        self._audio_model = audio_model
        self._client = AsyncOpenAI(api_key=api_key) if api_key else None

    async def interact(
        self,
        *,
        message: str | None,
        audio_path: Path | None,
        audio_mime_type: str | None,
        interaction_mode: str,
        now: str,
        timezone: str,
        context: ButlerContext,
        request_id: uuid.UUID | None = None,
    ) -> ButlerInteractionProposal:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        payload: dict[str, object] = {
            "interaction_mode": interaction_mode,
            "now": now,
            "timezone": timezone,
            "context": context.model_dump(mode="json"),
            "output_schema": ButlerInteractionProposal.model_json_schema(),
        }
        if audio_path is None and message is None:
            raise ButlerAIUnavailableError("Butler provider received no input")

        content: list[dict[str, object]] = [
            {
                "type": "text",
                "text": json.dumps({"message": message, **payload}, ensure_ascii=True),
            }
        ]
        input_bytes = 0
        if audio_path is not None:
            audio = await self._wav_audio(audio_path, audio_mime_type, request_id)
            input_bytes = audio_path.stat().st_size
            content.append(
                {
                    "type": "input_audio",
                    "input_audio": {
                        "data": base64.b64encode(audio).decode("ascii"),
                        "format": "wav",
                    },
                }
            )

        model = self._audio_model if audio_path is not None else self._model
        started = time.perf_counter()
        logger.info(
            "Butler interaction started request_id=%s api=chat_completions model=%s "
            "input_type=%s input_bytes=%d",
            request_id,
            model,
            audio_mime_type or "text/plain",
            input_bytes,
        )
        try:
            proposal = await self._chat_interaction(content, model=model)
            logger.info(
                "Butler interaction completed request_id=%s api=chat_completions model=%s "
                "elapsed_ms=%d action=%s intent=%s",
                request_id,
                model,
                round((time.perf_counter() - started) * 1000),
                proposal.decision.requested_action,
                proposal.decision.intent,
            )
            return proposal
        except (
            APIError,
            IndexError,
            OSError,
            ValidationError,
            json.JSONDecodeError,
        ) as exc:
            logger.warning(
                "Butler interaction failed request_id=%s api=chat_completions model=%s "
                "elapsed_ms=%d error_type=%s",
                request_id,
                model,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Multimodal AI provider request failed") from exc

    async def _chat_interaction(
        self, content: list[dict[str, object]], *, model: str
    ) -> ButlerInteractionProposal:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        completion = await self._client.chat.completions.create(
            model=model,
            messages=cast(
                Any,
                [
                    {"role": "system", "content": BUTLER_INTERACTION_INSTRUCTIONS},
                    {"role": "user", "content": content},
                ],
            ),
            tools=cast(
                Any,
                [
                    {
                        "type": "function",
                        "function": {
                            "name": _INTERACTION_TOOL,
                            "description": (
                                "Return the complete proposed Butler interaction. "
                                "Application code validates and applies mutations."
                            ),
                            "parameters": ButlerInteractionProposal.model_json_schema(),
                        },
                    }
                ],
            ),
            tool_choice=cast(
                Any,
                {"type": "function", "function": {"name": _INTERACTION_TOOL}},
            ),
            store=False,
        )
        assistant = completion.choices[0].message
        call = next(
            (
                item
                for item in assistant.tool_calls or []
                if item.type == "function" and item.function.name == _INTERACTION_TOOL
            ),
            None,
        )
        if call is None:
            raise ButlerAIUnavailableError(
                "Chat Completions returned no structured Butler interaction"
            )
        return ButlerInteractionProposal.model_validate_json(call.function.arguments)

    @staticmethod
    def _is_wav(data: bytes) -> bool:
        return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE"

    async def _wav_audio(
        self,
        path: Path,
        mime_type: str | None,
        request_id: uuid.UUID | None = None,
    ) -> bytes:
        input_bytes = path.stat().st_size
        if mime_type != "audio/ogg":
            logger.warning(
                "Input audio rejected request_id=%s cause=unsupported_mime mime=%s bytes=%d",
                request_id,
                mime_type,
                input_bytes,
            )
            raise ButlerAIUnavailableError("Uploaded audio must use audio/ogg")
        header = await asyncio.to_thread(self._read_prefix, path, 256)
        if not header.startswith(b"OggS") or b"OpusHead" not in header:
            logger.warning(
                "Input audio rejected request_id=%s cause=invalid_ogg bytes=%d",
                request_id,
                input_bytes,
            )
            raise ButlerAIUnavailableError("Uploaded audio is not valid Ogg/Opus")
        try:
            process = await asyncio.create_subprocess_exec(
                "ffmpeg",
                "-v",
                "error",
                "-i",
                str(path),
                "-ac",
                "1",
                "-ar",
                "16000",
                "-f",
                "wav",
                "pipe:1",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await process.communicate()
        except FileNotFoundError as exc:
            logger.error(
                "Input audio conversion failed request_id=%s cause=ffmpeg_unavailable bytes=%d",
                request_id,
                input_bytes,
            )
            raise ButlerAIUnavailableError("FFmpeg is unavailable for audio conversion") from exc
        except OSError as exc:
            logger.error(
                "Input audio conversion failed request_id=%s "
                "cause=ffmpeg_start_failed error_type=%s",
                request_id,
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Audio format conversion is unavailable") from exc
        if process.returncode != 0 or not stdout:
            logger.warning(
                "Input audio conversion failed request_id=%s cause=opus_to_wav_failed "
                "input_bytes=%d exit_code=%s stderr=%s",
                request_id,
                input_bytes,
                process.returncode,
                stderr.decode(errors="replace")[:500],
            )
            raise ButlerAIUnavailableError("Uploaded audio could not be decoded")
        if not self._is_wav(stdout):
            logger.warning(
                "Input audio conversion failed request_id=%s "
                "cause=invalid_converted_wav output_bytes=%d",
                request_id,
                len(stdout),
            )
            raise ButlerAIUnavailableError("Uploaded audio conversion produced invalid WAV")
        logger.info(
            "Input audio converted request_id=%s format=ogg/opus_to_wav "
            "input_bytes=%d output_bytes=%d",
            request_id,
            input_bytes,
            len(stdout),
        )
        return stdout

    @staticmethod
    def _read_prefix(path: Path, size: int) -> bytes:
        with path.open("rb") as source:
            return source.read(size)

    async def plan_day(self, planning_input: DayPlanningInput) -> PlannedDayProposal:
        return await self._parse(
            instructions=DAY_PLANNING_INSTRUCTIONS,
            payload=planning_input.model_dump(mode="json"),
            text_format=PlannedDayProposal,
        )

    async def compose_morning_brief(
        self, planning_input: DayPlanningInput, final_events: list[dict[str, object]]
    ) -> MorningBriefDraft:
        return await self._parse(
            instructions=MORNING_BRIEF_INSTRUCTIONS,
            payload={
                "planning_input": planning_input.model_dump(mode="json"),
                "final_events": final_events,
            },
            text_format=MorningBriefDraft,
        )

    async def compose_good_night_summary(
        self, summary_input: GoodNightSummaryInput
    ) -> GoodNightSummaryDraft:
        return await self._parse(
            instructions=GOOD_NIGHT_SUMMARY_INSTRUCTIONS,
            payload=summary_input.model_dump(mode="json"),
            text_format=GoodNightSummaryDraft,
        )

    async def _parse(
        self,
        *,
        instructions: str,
        payload: object,
        text_format: type[ModelT],
    ) -> ModelT:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        try:
            response = await self._client.responses.parse(
                model=self._model,
                instructions=instructions,
                input=json.dumps(payload, ensure_ascii=True),
                text_format=text_format,
                store=False,
            )
        except APIError as exc:
            raise ButlerAIUnavailableError("AI provider request failed") from exc
        if response.output_parsed is None:
            raise ButlerAIUnavailableError("AI provider returned no structured output")
        return response.output_parsed
