from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import cast

from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import ButlerResult, ChangedEntity, InteractionMode
from app.application.butler.service import ButlerService
from app.application.push.service import PushService
from app.core.config import Settings
from app.infrastructure.db.models import ButlerRequestModel

logger = logging.getLogger(__name__)


class ButlerRequestAccepted(BaseModel):
    request_id: uuid.UUID
    status: str = "accepted"


class ButlerRequestResult(BaseModel):
    request_id: uuid.UUID
    status: str
    input_source: str
    interaction_mode: str
    user_message_text: str | None = None
    response_text: str | None = None
    response_audio_url: str | None = None
    response_audio_mime_type: str | None = None
    response_audio_duration_ms: int | None = None
    changed_entities: list[ChangedEntity] = Field(default_factory=list)
    requires_follow_up: bool = False
    created_at: datetime
    completed_at: datetime | None = None
    failure_reason: str | None = None
    warnings: list[str] = Field(default_factory=list)


class ButlerRequestConflict(RuntimeError):
    pass


class ButlerRequestService:
    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        butler: ButlerService,
        push: PushService,
    ) -> None:
        self._settings = settings
        self._sessions = session_factory
        self._butler = butler
        self._push = push
        self._processing: set[uuid.UUID] = set()
        self._lock = asyncio.Lock()

    async def accept_text(
        self, *, request_id: uuid.UUID, user_id: uuid.UUID, message: str
    ) -> ButlerRequestAccepted:
        if not message.strip():
            raise ValueError("message must not be blank")
        status = await self._accept(
            ButlerRequestModel(
                id=request_id,
                user_id=user_id,
                input_source="text",
                interaction_mode="talk",
                submitted_text=message,
            )
        )
        return ButlerRequestAccepted(request_id=request_id, status=status)

    async def accept_audio(
        self,
        *,
        request_id: uuid.UUID,
        user_id: uuid.UUID,
        interaction_mode: InteractionMode,
        path: Path,
        mime_type: str,
    ) -> ButlerRequestAccepted:
        status = await self._accept(
            ButlerRequestModel(
                id=request_id,
                user_id=user_id,
                input_source="audio",
                interaction_mode=interaction_mode,
                input_audio_path=str(path),
                input_audio_mime_type=mime_type,
            )
        )
        return ButlerRequestAccepted(request_id=request_id, status=status)

    async def _accept(self, incoming: ButlerRequestModel) -> str:
        superseded: str | None = None
        async with self._sessions() as session, session.begin():
            current = await session.get(ButlerRequestModel, incoming.id, with_for_update=True)
            if current is None:
                session.add(incoming)
                return "accepted"
            same = (
                current.user_id == incoming.user_id
                and current.input_source == incoming.input_source
                and current.interaction_mode == incoming.interaction_mode
                and current.submitted_text == incoming.submitted_text
            )
            if not same:
                raise ButlerRequestConflict("request_id belongs to different request content")
            if current.status == "failed":
                current.status = "accepted"
                current.failure_reason = None
                status = "accepted"
            else:
                status = current.status
            if status == "accepted" and incoming.input_audio_path:
                superseded = current.input_audio_path
                current.input_audio_path = incoming.input_audio_path
                current.input_audio_mime_type = incoming.input_audio_mime_type
        if superseded and superseded != incoming.input_audio_path:
            await self._delete_file(superseded)
        return status

    async def process(self, request_id: uuid.UUID) -> None:
        async with self._lock:
            if request_id in self._processing:
                return
            self._processing.add(request_id)
        try:
            request = await self._claim(request_id)
            if request is None:
                return
            logger.info(
                "Processing Butler request request_id=%s mode=%s source=%s audio_type=%s",
                request.id,
                request.interaction_mode,
                request.input_source,
                request.input_audio_mime_type,
            )
            await self._push.butler_request_state(
                request.user_id, "butler_request_handling", request.id
            )
            audio_path = Path(request.input_audio_path) if request.input_audio_path else None
            if request.input_source == "audio" and audio_path is None:
                raise RuntimeError("Accepted audio request has no input asset")
            interaction = await self._butler.handle(
                request_id=request.id,
                user_id=request.user_id,
                interaction_mode=cast(InteractionMode, request.interaction_mode),
                message=request.submitted_text,
                audio_path=audio_path,
                audio_mime_type=request.input_audio_mime_type,
            )
            response_audio_path, audio_type = await self._response_audio(
                request.id,
                interaction.response_audio,
                interaction.response_audio_mime_type,
            )
            await self._complete(request.id, interaction.result, response_audio_path, audio_type)
            try:
                await self._delete_file(request.input_audio_path)
                await self._clear_input_path(request.id)
                if request.input_audio_path:
                    logger.info(
                        "Temporary input audio deleted request_id=%s path=%s",
                        request.id,
                        request.input_audio_path,
                    )
            except Exception:
                logger.exception(
                    "Temporary input audio cleanup failed request_id=%s; "
                    "maintenance cleanup retained",
                    request.id,
                )
            await self._push.butler_request_state(
                request.user_id, "butler_request_completed", request.id
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("Butler request %s failed", request_id)
            await self._fail(request_id, exc)
        finally:
            async with self._lock:
                self._processing.discard(request_id)

    async def _claim(self, request_id: uuid.UUID) -> ButlerRequestModel | None:
        stale = datetime.now(UTC) - timedelta(
            minutes=self._settings.butler_processing_stale_minutes
        )
        async with self._sessions() as session, session.begin():
            request = await session.get(ButlerRequestModel, request_id, with_for_update=True)
            if request is None or request.status == "completed":
                return None
            if request.status == "processing" and request.updated_at >= stale:
                return None
            request.status = "processing"
            request.failure_reason = None
            await session.flush()
            session.expunge(request)
            return request

    async def _response_audio(
        self, request_id: uuid.UUID, audio: bytes, mime_type: str
    ) -> tuple[Path | None, str | None]:
        path = Path(self._settings.butler_audio_root) / "responses" / f"{request_id}.ogg"
        if not audio:
            logger.warning(
                "Response audio unavailable request_id=%s cause=openai_no_usable_audio; "
                "canonical text/action preserved",
                request_id,
            )
            return None, None
        if mime_type != "audio/wav":
            logger.warning(
                "Response audio unavailable request_id=%s cause=unsupported_provider_mime "
                "mime=%s bytes=%d; canonical text/action preserved",
                request_id,
                mime_type,
                len(audio),
            )
            return None, None
        if len(audio) < 12 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
            logger.warning(
                "Response audio unavailable request_id=%s cause=invalid_openai_wav bytes=%d; "
                "canonical text/action preserved",
                request_id,
                len(audio),
            )
            return None, None
        logger.info(
            "Response WAV validated request_id=%s bytes=%d",
            request_id,
            len(audio),
        )
        try:
            encoded = await self._encode_response_ogg(request_id, audio)
            path.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(path.write_bytes, encoded)
            logger.info(
                "Response audio stored request_id=%s mime=audio/ogg bytes=%d "
                "path=%s available=true",
                request_id,
                len(encoded),
                path,
            )
            return path, "audio/ogg"
        except (FileNotFoundError, ValueError, OSError) as exc:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                logger.warning(
                    "Partial response audio cleanup failed request_id=%s path=%s",
                    request_id,
                    path,
                    exc_info=True,
                )
            cause = (
                "ffmpeg_unavailable"
                if isinstance(exc, FileNotFoundError)
                else "storage_failed"
                if isinstance(exc, OSError)
                else "wav_to_opus_failed"
            )
            logger.warning(
                "Response audio unavailable request_id=%s cause=%s error_type=%s; "
                "canonical text/action preserved",
                request_id,
                cause,
                type(exc).__name__,
                exc_info=True,
            )
            return None, None

    async def _encode_response_ogg(self, request_id: uuid.UUID, wav: bytes) -> bytes:
        try:
            process = await asyncio.create_subprocess_exec(
                "ffmpeg",
                "-v",
                "error",
                "-f",
                "wav",
                "-i",
                "pipe:0",
                "-map_metadata",
                "-1",
                "-c:a",
                "libopus",
                "-b:a",
                "32k",
                "-vbr",
                "on",
                "-f",
                "ogg",
                "pipe:1",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError:
            logger.error(
                "Response conversion failed request_id=%s cause=ffmpeg_unavailable wav_bytes=%d",
                request_id,
                len(wav),
            )
            raise
        except OSError as exc:
            logger.error(
                "Response conversion failed request_id=%s cause=ffmpeg_start_failed error_type=%s",
                request_id,
                type(exc).__name__,
            )
            raise ValueError("Response audio encoder could not start") from exc
        encoded, stderr = await process.communicate(wav)
        if process.returncode != 0 or not encoded:
            logger.warning(
                "Response conversion failed request_id=%s cause=wav_to_opus_failed "
                "wav_bytes=%d exit_code=%s stderr=%s",
                request_id,
                len(wav),
                process.returncode,
                stderr.decode(errors="replace")[:500],
            )
            raise ValueError("Response WAV could not be encoded as Ogg/Opus")
        if not self._is_ogg_opus(encoded):
            logger.warning(
                "Response conversion failed request_id=%s cause=invalid_encoded_ogg bytes=%d",
                request_id,
                len(encoded),
            )
            raise ValueError("Encoded response is not valid Ogg/Opus")
        logger.info(
            "Response audio converted request_id=%s format=wav_to_ogg_opus "
            "wav_bytes=%d opus_bytes=%d",
            request_id,
            len(wav),
            len(encoded),
        )
        return encoded

    @staticmethod
    def _is_ogg_opus(data: bytes) -> bool:
        return len(data) >= 32 and data[:4] == b"OggS" and b"OpusHead" in data[:256]

    async def _complete(
        self,
        request_id: uuid.UUID,
        result: ButlerResult,
        audio_path: Path | None,
        audio_type: str | None,
    ) -> None:
        now = datetime.now(UTC)
        async with self._sessions() as session, session.begin():
            request = await session.get(ButlerRequestModel, request_id, with_for_update=True)
            if request is None or request.status == "completed":
                return
            request.user_message_text = result.user_message_text
            request.response_text = result.response
            request.response_audio_path = str(audio_path) if audio_path else None
            request.response_audio_mime_type = audio_type
            if request.input_audio_path:
                request.input_audio_delete_after = now
            request.response_audio_delete_after = (
                now + timedelta(days=self._settings.butler_response_audio_retention_days)
                if audio_path
                else None
            )
            request.changed_entities = [
                item.model_dump(mode="json") for item in result.changed_entities
            ]
            request.requires_follow_up = result.requires_follow_up
            request.status = "completed"
            request.completed_at = now
            request.failure_reason = None

    async def _fail(self, request_id: uuid.UUID, error: Exception) -> None:
        async with self._sessions() as session, session.begin():
            request = await session.get(ButlerRequestModel, request_id, with_for_update=True)
            if request is None or request.status == "completed":
                return
            request.status = "failed"
            request.failure_reason = type(error).__name__
            if request.input_audio_path:
                request.input_audio_delete_after = datetime.now(UTC) + timedelta(
                    hours=self._settings.butler_input_audio_failure_retention_hours
                )

    async def result(self, user_id: uuid.UUID, request_id: uuid.UUID) -> ButlerRequestResult | None:
        async with self._sessions() as session:
            request = await session.scalar(
                select(ButlerRequestModel).where(
                    ButlerRequestModel.id == request_id,
                    ButlerRequestModel.user_id == user_id,
                )
            )
        if request is None:
            return None
        if request.status == "completed":
            logger.info(
                "Butler result exposed request_id=%s response_audio_available=%s mime=%s",
                request.id,
                bool(request.response_audio_path),
                request.response_audio_mime_type,
            )
        return ButlerRequestResult(
            request_id=request.id,
            status=request.status,
            input_source=request.input_source,
            interaction_mode=request.interaction_mode,
            user_message_text=request.user_message_text,
            response_text=request.response_text,
            response_audio_url=f"/api/butler/requests/{request.id}/audio"
            if request.response_audio_path
            else None,
            response_audio_mime_type=request.response_audio_mime_type,
            response_audio_duration_ms=request.response_audio_duration_ms,
            changed_entities=[
                ChangedEntity.model_validate(item) for item in request.changed_entities
            ],
            requires_follow_up=request.requires_follow_up,
            created_at=request.created_at,
            completed_at=request.completed_at,
            failure_reason=request.failure_reason,
            warnings=(
                ["Response audio is unavailable; the text response is still complete."]
                if request.status == "completed"
                and request.response_text
                and not request.response_audio_path
                else []
            ),
        )

    async def recent_results(
        self, user_id: uuid.UUID, limit: int = 50
    ) -> list[ButlerRequestResult]:
        async with self._sessions() as session:
            ids = list(
                (
                    await session.scalars(
                        select(ButlerRequestModel.id)
                        .where(
                            ButlerRequestModel.user_id == user_id,
                            ButlerRequestModel.status == "completed",
                        )
                        .order_by(ButlerRequestModel.completed_at.desc())
                        .limit(limit)
                    )
                ).all()
            )
        results = [await self.result(user_id, item) for item in reversed(ids)]
        return [item for item in results if item is not None]

    async def audio_path(self, user_id: uuid.UUID, request_id: uuid.UUID) -> Path | None:
        async with self._sessions() as session:
            request = await session.scalar(
                select(ButlerRequestModel).where(
                    ButlerRequestModel.id == request_id,
                    ButlerRequestModel.user_id == user_id,
                    ButlerRequestModel.status == "completed",
                )
            )
        path = (
            Path(request.response_audio_path) if request and request.response_audio_path else None
        )
        return path if path and path.is_file() else None

    async def recover(self) -> list[uuid.UUID]:
        stale = datetime.now(UTC) - timedelta(
            minutes=self._settings.butler_processing_stale_minutes
        )
        async with self._sessions() as session:
            return list(
                (
                    await session.scalars(
                        select(ButlerRequestModel.id).where(
                            or_(
                                ButlerRequestModel.status == "accepted",
                                (ButlerRequestModel.status == "processing")
                                & (ButlerRequestModel.updated_at < stale),
                            )
                        )
                    )
                ).all()
            )

    async def cleanup_audio(self) -> None:
        now = datetime.now(UTC)
        async with self._sessions() as session, session.begin():
            requests = list(
                (
                    await session.scalars(
                        select(ButlerRequestModel).where(
                            or_(
                                ButlerRequestModel.input_audio_delete_after <= now,
                                ButlerRequestModel.response_audio_delete_after <= now,
                            )
                        )
                    )
                ).all()
            )
            for request in requests:
                if request.input_audio_delete_after and request.input_audio_delete_after <= now:
                    try:
                        await self._delete_file(request.input_audio_path)
                    except OSError:
                        logger.warning(
                            "Expired retained input audio cleanup failed request_id=%s path=%s",
                            request.id,
                            request.input_audio_path,
                            exc_info=True,
                        )
                    else:
                        logger.info(
                            "Expired retained input audio deleted request_id=%s path=%s",
                            request.id,
                            request.input_audio_path,
                        )
                        request.input_audio_path = None
                        request.input_audio_delete_after = None
                if (
                    request.response_audio_delete_after
                    and request.response_audio_delete_after <= now
                ):
                    try:
                        await self._delete_file(request.response_audio_path)
                    except OSError:
                        logger.warning(
                            "Expired response audio cleanup failed request_id=%s path=%s",
                            request.id,
                            request.response_audio_path,
                            exc_info=True,
                        )
                    else:
                        logger.info(
                            "Expired response audio deleted request_id=%s path=%s",
                            request.id,
                            request.response_audio_path,
                        )
                        request.response_audio_path = None
                        request.response_audio_delete_after = None

    @staticmethod
    async def _delete_file(path: str | None) -> None:
        if path:
            await asyncio.to_thread(Path(path).unlink, missing_ok=True)

    async def _clear_input_path(self, request_id: uuid.UUID) -> None:
        async with self._sessions() as session, session.begin():
            request = await session.get(ButlerRequestModel, request_id)
            if request is not None:
                request.input_audio_path = None
                request.input_audio_delete_after = None
