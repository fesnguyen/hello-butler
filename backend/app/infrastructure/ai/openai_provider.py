from __future__ import annotations

import asyncio
import base64
import binascii
import json
import logging
import re
import tempfile
import time
import uuid
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, TypeVar, cast

from openai import APIError, AsyncOpenAI
from pydantic import BaseModel, ValidationError

from app.application.butler import (
    ButlerAIInteraction,
    ButlerAIUnavailableError,
    ButlerContext,
    ButlerInteractionProposal,
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
    ) -> ButlerAIInteraction:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        payload: dict[str, object] = {
            "interaction_mode": interaction_mode,
            "now": now,
            "timezone": timezone,
            "context": context.model_dump(mode="json"),
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

        started = time.perf_counter()
        logger.info(
            "Single-call Butler interaction started request_id=%s model=%s "
            "input_type=%s input_bytes=%d",
            request_id,
            self._audio_model,
            audio_mime_type or "text/plain",
            input_bytes,
        )
        tool = {
            "type": "function",
            "function": {
                "name": _INTERACTION_TOOL,
                "description": (
                    "Return the complete proposed Butler interaction. Application code "
                    "validates and applies all proposed mutations."
                ),
                "parameters": ButlerInteractionProposal.model_json_schema(),
            },
        }
        messages = [
            {"role": "system", "content": BUTLER_INTERACTION_INSTRUCTIONS},
            {"role": "user", "content": content},
        ]
        try:
            completion = await self._client.chat.completions.create(
                model=self._audio_model,
                modalities=["text", "audio"],
                audio=cast(Any, {"voice": self._audio_voice, "format": "wav"}),
                messages=cast(Any, messages),
                tools=cast(Any, [tool]),
                tool_choice=cast(
                    Any,
                    {"type": "function", "function": {"name": _INTERACTION_TOOL}},
                ),
                store=False,
            )
            assistant = completion.choices[0].message
            calls = assistant.tool_calls or []
            call = next(
                (
                    item
                    for item in calls
                    if item.type == "function" and item.function.name == _INTERACTION_TOOL
                ),
                None,
            )
            if call is None:
                raise ButlerAIUnavailableError(
                    "Multimodal AI provider returned no Butler interaction"
                )
            proposal = ButlerInteractionProposal.model_validate_json(call.function.arguments)
            output = assistant.audio
            if output is None:
                logger.warning(
                    "Response audio unavailable request_id=%s cause=openai_no_audio; "
                    "using text-only fallback",
                    request_id,
                )
                response_audio = b""
            else:
                try:
                    response_audio = base64.b64decode(output.data, validate=True)
                    logger.info(
                        "OpenAI response audio returned request_id=%s returned=true "
                        "decoded_bytes=%d",
                        request_id,
                        len(response_audio),
                    )
                except binascii.Error:
                    logger.warning(
                        "Response audio unavailable request_id=%s cause=base64_decode_failed",
                        request_id,
                        exc_info=True,
                    )
                    response_audio = b""
                if response_audio:
                    try:
                        response_audio = await self._normalize_response_wav(response_audio)
                    except ButlerAIUnavailableError as exc:
                        logger.warning(
                            "Response audio unavailable request_id=%s "
                            "cause=wav_normalization_failed detail=%s",
                            request_id,
                            exc,
                            exc_info=True,
                        )
                        response_audio = b""
                score = self._message_similarity(output.transcript, proposal.response_text)
                if response_audio and not self._same_message(
                    output.transcript, proposal.response_text
                ):
                    logger.warning(
                        "Response audio unavailable request_id=%s cause=materially_unrelated "
                        "similarity=%.2f; canonical text/action preserved",
                        request_id,
                        score,
                    )
                    response_audio = b""
                elif response_audio:
                    logger.info(
                        "Response audio consistency accepted request_id=%s similarity=%.2f",
                        request_id,
                        score,
                    )
            logger.info(
                "Single-call Butler interaction completed request_id=%s model=%s elapsed_ms=%d "
                "response_audio_bytes=%d action=%s intent=%s",
                request_id,
                self._audio_model,
                round((time.perf_counter() - started) * 1000),
                len(response_audio),
                proposal.decision.requested_action,
                proposal.decision.intent,
            )
            return ButlerAIInteraction(
                proposal=proposal,
                response_audio=response_audio,
                response_audio_mime_type="audio/wav",
            )
        except (
            APIError,
            IndexError,
            OSError,
            ValidationError,
            json.JSONDecodeError,
            binascii.Error,
        ) as exc:
            logger.warning(
                "Single-call Butler interaction failed request_id=%s model=%s "
                "elapsed_ms=%d error_type=%s",
                request_id,
                self._audio_model,
                round((time.perf_counter() - started) * 1000),
                type(exc).__name__,
            )
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
                raise ButlerAIUnavailableError("Generated response audio could not be normalized")

            normalized = await asyncio.to_thread(output_path.read_bytes)

            if not self._is_wav(normalized):
                raise ButlerAIUnavailableError("Normalized response audio is not WAV")

            logger.info(
                "Response audio normalized input_bytes=%d output_bytes=%d",
                len(data),
                len(normalized),
            )

            return normalized

        except FileNotFoundError as exc:
            logger.error("Response WAV normalization failed cause=ffmpeg_unavailable")
            raise ButlerAIUnavailableError(
                "Response audio normalization failed because FFmpeg is unavailable"
            ) from exc

        except OSError as exc:
            logger.error(
                "Response WAV normalization failed cause=ffmpeg_start_failed error_type=%s",
                type(exc).__name__,
            )
            raise ButlerAIUnavailableError("Response audio normalization is unavailable") from exc

        finally:
            if input_path is not None:
                input_path.unlink(missing_ok=True)
            if output_path is not None:
                output_path.unlink(missing_ok=True)

    @staticmethod
    def _same_message(spoken: str, canonical: str) -> bool:
        spoken_words = OpenAIButlerProvider._message_words(spoken)
        canonical_words = OpenAIButlerProvider._message_words(canonical)
        if not spoken_words or not canonical_words:
            return False
        stop_words = {
            "a",
            "an",
            "and",
            "at",
            "for",
            "i",
            "in",
            "is",
            "it",
            "of",
            "on",
            "the",
            "to",
            "was",
            "your",
        }
        shared = (set(spoken_words) & set(canonical_words)) - stop_words
        return (
            OpenAIButlerProvider._message_similarity(spoken, canonical) >= 0.30 or len(shared) >= 2
        )

    @staticmethod
    def _message_similarity(spoken: str, canonical: str) -> float:
        spoken_words = OpenAIButlerProvider._message_words(spoken)
        canonical_words = OpenAIButlerProvider._message_words(canonical)
        if not spoken_words or not canonical_words:
            return 0.0
        spoken_text = " ".join(spoken_words)
        canonical_text = " ".join(canonical_words)
        sequence_score = SequenceMatcher(None, spoken_text, canonical_text).ratio()
        overlap = sum((Counter(spoken_words) & Counter(canonical_words)).values())
        token_score = (2 * overlap) / (len(spoken_words) + len(canonical_words))
        return max(sequence_score, token_score)

    @staticmethod
    def _message_words(value: str) -> list[str]:
        return re.findall(r"\w+", value.casefold())

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
