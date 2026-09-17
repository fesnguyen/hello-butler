from __future__ import annotations

import asyncio
import base64
import binascii
import json
import logging
import re
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path
import tempfile
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
    ) -> ButlerUnderstanding:
        payload: dict[str, object] = {
            "interaction_mode": interaction_mode,
            "now": now,
            "timezone": timezone,
            "context": context.model_dump(mode="json"),
        }
        if audio_path is not None:
            return await self._understand_audio(audio_path, audio_mime_type, payload)
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
    ) -> ButlerUnderstanding:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        audio = await self._wav_audio(path)
        logger.info(
            "Calling multimodal Butler model=%s input_type=%s input_bytes=%d",
            self._audio_model,
            mime_type or "unknown",
            path.stat().st_size,
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
                    if item.type == "function"
                    and item.function.name == _UNDERSTANDING_TOOL
                ),
                None,
            )
            if call is None:
                raise ButlerAIUnavailableError(
                    "Multimodal AI provider returned no Butler understanding"
                )
            return ButlerUnderstanding.model_validate_json(call.function.arguments)
        except (APIError, IndexError, OSError, ValidationError, json.JSONDecodeError) as exc:
            raise ButlerAIUnavailableError("Multimodal AI provider request failed") from exc

    async def _normalize_response_wav(self, data: bytes) -> bytes:
        input_path: Path | None = None
        output_path: Path | None = None

        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as input_file:
                input_file.write(data)
                input_path = Path(input_file.name)

            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as output_file:
                output_path = Path(output_file.name)

            # Let FFmpeg create the output file itself.
            output_path.unlink()

            process = await asyncio.create_subprocess_exec(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-i",
                str(input_path),
                "-map_metadata",
                "-1",
                "-c:a",
                "pcm_s16le",
                "-ac",
                "1",
                "-ar",
                "24000",
                "-f",
                "wav",
                str(output_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )

            _, stderr = await process.communicate()

            if process.returncode != 0 or not output_path.is_file():
                logger.warning(
                    "Response audio normalization failed exit_code=%s error=%s",
                    process.returncode,
                    stderr.decode(errors="replace"),
                )
                raise ButlerAIUnavailableError(
                    "Generated response audio could not be normalized"
                )

            normalized = await asyncio.to_thread(output_path.read_bytes)

            if not self._is_wav(normalized):
                raise ButlerAIUnavailableError(
                    "Normalized response audio is not WAV"
                )

            logger.info(
                "Response audio normalized input_bytes=%d output_bytes=%d",
                len(data),
                len(normalized),
            )

            return normalized

        except OSError as exc:
            raise ButlerAIUnavailableError(
                "Response audio normalization is unavailable"
            ) from exc

        finally:
            if input_path is not None:
                input_path.unlink(missing_ok=True)
            if output_path is not None:
                output_path.unlink(missing_ok=True)

    async def synthesize(self, text: str, path: Path) -> str:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
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
                logger.warning(
                    "Audio model transcript differs from canonical response similarity=%.2f; "
                    "accepting generated audio",
                    self._message_similarity(output.transcript, text),
                )
            data = base64.b64decode(output.data, validate=True)
            if not data:
                raise ButlerAIUnavailableError("Audio model returned empty response audio")
            
            data = await self._normalize_response_wav(data)  # normalize the response audio to ensure it is a valid WAV file

            path.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(path.write_bytes, data)
        except (APIError, IndexError, OSError, binascii.Error) as exc:
            path.unlink(missing_ok=True)
            raise ButlerAIUnavailableError("Response audio generation failed") from exc
        return "audio/wav"

    @staticmethod
    def _same_message(spoken: str, canonical: str) -> bool:
        return OpenAIButlerProvider._message_similarity(spoken, canonical) >= 0.72

    @staticmethod
    def _message_similarity(spoken: str, canonical: str) -> float:
        spoken_words = re.findall(r"\w+", spoken.casefold())
        canonical_words = re.findall(r"\w+", canonical.casefold())
        if not spoken_words or not canonical_words:
            return 0.0
        spoken_text = " ".join(spoken_words)
        canonical_text = " ".join(canonical_words)
        sequence_score = SequenceMatcher(None, spoken_text, canonical_text).ratio()
        overlap = sum((Counter(spoken_words) & Counter(canonical_words)).values())
        token_score = (2 * overlap) / (len(spoken_words) + len(canonical_words))
        return max(sequence_score, token_score)

    @staticmethod
    def _is_wav(data: bytes) -> bool:
        return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE"

    async def _wav_audio(self, path: Path) -> bytes:
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
            raise ButlerAIUnavailableError("Audio format conversion is unavailable") from exc
        if process.returncode != 0 or not stdout:
            logger.warning("Audio format conversion failed exit_code=%s", process.returncode)
            raise ButlerAIUnavailableError("Uploaded audio could not be decoded")
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
