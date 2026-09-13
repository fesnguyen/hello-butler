from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.audio import ButlerAudioProvider
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


class ButlerRequestConflict(RuntimeError):
    pass


class ButlerRequestService:
    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        butler: ButlerService,
        audio: ButlerAudioProvider,
        push: PushService,
    ) -> None:
        self._settings = settings
        self._sessions = session_factory
        self._butler = butler
        self._audio = audio
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
            await self._push.butler_request_state(
                request.user_id, "butler_request_handling", request.id
            )
            message = request.submitted_text
            if request.input_source == "audio":
                if not request.input_audio_path:
                    raise RuntimeError("Accepted audio request has no input asset")
                message = await self._audio.transcribe(Path(request.input_audio_path))
            if not message:
                raise RuntimeError("Request normalization produced no message")
            result = await self._butler.handle(
                request_id=request.id,
                user_id=request.user_id,
                interaction_mode=request.interaction_mode,
                message=message,
            )  # type: ignore[arg-type]
            audio_path, audio_type = await self._response_audio(request.id, result)
            await self._complete(request.id, message, result, audio_path, audio_type)
            try:
                await self._delete_file(request.input_audio_path)
                await self._clear_input_path(request.id)
            except OSError:
                logger.exception("Input audio cleanup deferred for request %s", request.id)
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
        self, request_id: uuid.UUID, result: ButlerResult
    ) -> tuple[Path | None, str | None]:
        path = Path(self._settings.butler_audio_root) / "responses" / f"{request_id}.mp3"
        try:
            return path, await self._audio.synthesize(result.response, path)
        except Exception:
            logger.exception("Response audio generation failed for request %s", request_id)
            return None, None

    async def _complete(
        self,
        request_id: uuid.UUID,
        message: str,
        result: ButlerResult,
        audio_path: Path | None,
        audio_type: str | None,
    ) -> None:
        now = datetime.now(UTC)
        async with self._sessions() as session, session.begin():
            request = await session.get(ButlerRequestModel, request_id, with_for_update=True)
            if request is None or request.status == "completed":
                return
            request.user_message_text = message
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
                    ButlerRequestModel.id == request_id, ButlerRequestModel.user_id == user_id
                )
            )
        if request is None:
            return None
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
                    await self._delete_file(request.input_audio_path)
                    request.input_audio_path = None
                    request.input_audio_delete_after = None
                if (
                    request.response_audio_delete_after
                    and request.response_audio_delete_after <= now
                ):
                    await self._delete_file(request.response_audio_path)
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
