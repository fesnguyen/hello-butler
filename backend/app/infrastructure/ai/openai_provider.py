from __future__ import annotations

import asyncio
import base64
import binascii
import json
import logging
import re
import time
import uuid
from pathlib import Path
from typing import Any, TypeVar, cast

from openai import APIError, AsyncOpenAI
from pydantic import BaseModel, ValidationError

from app.application.butler import (
    ButlerAIUnavailableError,
    ButlerContext,
    ButlerDecision,
    ButlerUnderstanding,
)
from app.application.planning.contracts import (
    DayPlanningInput,
    GoodNightSummaryDraft,
    GoodNightSummaryInput,
    MorningBriefDraft,
    PlannedDayProposal,
)

from .prompts import (
    BUTLER_DECISION_INSTRUCTIONS,
    BUTLER_RESPONSE_AUDIO_INSTRUCTIONS,
    DAY_PLANNING_INSTRUCTIONS,
    GOOD_NIGHT_SUMMARY_INSTRUCTIONS,
    MORNING_BRIEF_INSTRUCTIONS,
)

logger = logging.getLogger(__name__)
ModelT = TypeVar("ModelT", bound=BaseModel)
_UNDERSTANDING_TOOL = "submit_butler_understanding"


class OpenAIButlerProvider:
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        audio_model: str = "gpt-audio-1.5",
        audio_voice: str = "alloy",
    ) -> None:
        self._model = model
        self._audio_model = audio_model
        self._audio_voice = audio_voice
        self._client = AsyncOpenAI(api_key=api_key) if api_key else None

    async def understand(
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
    ) -> ButlerUnderstanding:
        payload: dict[str, object] = {
            "interaction_mode": interaction_mode,
            "now": now,
            "timezone": timezone,
            "context": context.model_dump(mode="json"),
        }
        if audio_path is not None:
            return await self._understand_audio(audio_path, audio_mime_type, payload, request_id)
        if message is None:
            raise ButlerAIUnavailableError("Butler provider received no input")
        decision = await self._parse(
            instructions=BUTLER_DECISION_INSTRUCTIONS,
            payload={"message": message, **payload},
            text_format=ButlerDecision,
        )
        return ButlerUnderstanding(user_message_text=message, decision=decision)

    async def _understand_audio(
        self,
        path: Path,
        mime_type: str | None,
        payload: dict[str, object],
        request_id: uuid.UUID | None,
    ) -> ButlerUnderstanding:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        audio = await self._wav_audio(path, request_id)
        started = time.perf_counter()
        logger.info(
            "Multimodal audio request started request_id=%s model=%s input_type=%s "
            "encoded_bytes=%d decoded_bytes=%d",
            request_id,
            self._audio_model,
            mime_type or "unknown",
            path.stat().st_size,
            len(audio),
        )
        tool = {
            "type": "function",
            "function": {
                "name": _UNDERSTANDING_TOOL,
                "description": (
                    "Return the understood user utterance and Butler decision. "
                    "Application code validates and applies all mutations."
                ),
                "parameters": ButlerUnderstanding.model_json_schema(),
            },
        }
        messages = [
            {"role": "system", "content": BUTLER_DECISION_INSTRUCTIONS},
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": json.dumps(payload, ensure_ascii=True),
                    },
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": base64.b64encode(audio).decode("ascii"),
                            "format": "wav",
                        },
                    },
                ],
            },
        ]
        try:
            completion = await self._client.chat.completions.create(
                model=self._audio_model,
                messages=cast(Any, messages),
                tools=cast(Any, [tool]),
                tool_choice=cast(
                    Any,
                    {"type": "function", "function": {"name": _UNDERSTANDING_TOOL}},
                ),
                store=False,
            )
            calls = completion.choices[0].message.tool_calls or []
            call = next(
                (
                    item
                    for item in calls
                    if item.type == "function" and item.function.name == _UNDERSTANDING_TOOL
                ),
                None,
            )
            if call is None:
                raise ButlerAIUnavailableError(
                    "Multimodal AI provider returned no Butler understanding"
                )
            understanding = ButlerUnderstanding.model_validate_json(call.function.arguments)
            logger.info(
                "Multimodal audio request completed request_id=%s model=%s elapsed_ms=%d",
                request_id,
                self._audio_model,
                round((time.perf_counter() - started) * 1000),
            )
            return understanding
        except (APIError, IndexError, OSError, ValidationError, json.JSONDecodeError) as exc:
            logger.warning(
                "Multimodal audio request failed request_id=%s model=%s elapsed_ms=%d "
                "error_type=%s",
                request_id,
                self._audio_model,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Multimodal AI provider request failed") from exc

    async def synthesize(self, text: str, path: Path) -> str:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        started = time.perf_counter()
        logger.info(
            "Response audio generation started model=%s voice=%s text_chars=%d",
            self._audio_model,
            self._audio_voice,
            len(text),
        )
        try:
            completion = await self._client.chat.completions.create(
                model=self._audio_model,
                modalities=["text", "audio"],
                audio=cast(Any, {"voice": self._audio_voice, "format": "wav"}),
                messages=cast(
                    Any,
                    [
                        {"role": "system", "content": BUTLER_RESPONSE_AUDIO_INSTRUCTIONS},
                        {"role": "user", "content": text},
                    ],
                ),
                store=False,
            )
            output = completion.choices[0].message.audio
            if output is None:
                raise ButlerAIUnavailableError("Audio model returned no response audio")
            if not self._same_message(output.transcript, text):
                logger.warning("Audio model transcript did not match canonical response")
                raise ButlerAIUnavailableError("Response audio did not match canonical text")
            data = base64.b64decode(output.data, validate=True)
            if not data:
                raise ButlerAIUnavailableError("Audio model returned empty response audio")
            path.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(path.write_bytes, data)
        except (APIError, IndexError, OSError, binascii.Error) as exc:
            path.unlink(missing_ok=True)
            logger.warning(
                "Response audio generation failed model=%s elapsed_ms=%d error_type=%s",
                self._audio_model,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Response audio generation failed") from exc
        logger.info(
            "Response audio generation completed model=%s mime_type=audio/wav "
            "bytes=%d elapsed_ms=%d",
            self._audio_model,
            len(data),
            round((time.perf_counter() - started) * 1000),
        )
        return "audio/wav"

    @staticmethod
    def _same_message(spoken: str, canonical: str) -> bool:
        spoken_words = re.sub(r"[^\w]+", " ", spoken.casefold()).strip()
        canonical_words = re.sub(r"[^\w]+", " ", canonical.casefold()).strip()
        return spoken_words == canonical_words

    async def _wav_audio(self, path: Path, request_id: uuid.UUID | None = None) -> bytes:
        started = time.perf_counter()
        input_bytes = path.stat().st_size
        logger.info(
            "Audio decode started request_id=%s input_bytes=%d target_codec=pcm_s16le "
            "channels=1 sample_rate_hz=16000",
            request_id,
            input_bytes,
        )
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
            stdout, _stderr = await process.communicate()
        except OSError as exc:
            logger.warning(
                "Audio decode unavailable request_id=%s input_bytes=%d elapsed_ms=%d error_type=%s",
                request_id,
                input_bytes,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Audio format conversion is unavailable") from exc
        if process.returncode != 0 or not stdout:
            logger.warning(
                "Audio decode failed request_id=%s input_bytes=%d elapsed_ms=%d exit_code=%s",
                request_id,
                input_bytes,
                round((time.perf_counter() - started) * 1000),
                process.returncode,
            )
            raise ButlerAIUnavailableError("Uploaded audio could not be decoded")
        logger.info(
            "Audio decode completed request_id=%s input_bytes=%d output_bytes=%d "
            "expansion_ratio=%.2f elapsed_ms=%d",
            request_id,
            input_bytes,
            len(stdout),
            len(stdout) / input_bytes if input_bytes else 0,
            round((time.perf_counter() - started) * 1000),
        )
        return stdout

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
