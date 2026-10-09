"""Shared, durable speech generation for conversations and scheduled summaries."""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sqlalchemy import or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import ButlerVoiceProvider
from app.core.config import Settings
from app.infrastructure.db.models import ButlerRequestModel, DailyEventModel, UserModel

logger = logging.getLogger(__name__)


class SpeechService:
    def __init__(
        self,
        settings: Settings,
        sessions: async_sessionmaker[AsyncSession],
        openai: ButlerVoiceProvider | None,
        open_source: ButlerVoiceProvider | None,
    ) -> None:
        self.settings, self.sessions = settings, sessions
        self.openai, self.open_source = openai, open_source

    @staticmethod
    def _model(kind: str):
        if kind == "request":
            return ButlerRequestModel
        if kind == "event":
            return DailyEventModel
        raise ValueError("Unknown speech owner")

    @staticmethod
    async def prepare_reminder(
        session: AsyncSession, user_id: uuid.UUID, owner_id: uuid.UUID, version: int
    ) -> tuple[str, bool]:
        """Queue on-demand speech once; reuse the existing event owner/lifecycle."""
        event = await session.scalar(
            select(DailyEventModel)
            .where(
                DailyEventModel.id == owner_id,
                DailyEventModel.user_id == user_id,
                DailyEventModel.deleted_at.is_(None),
                DailyEventModel.status == "planned",
                DailyEventModel.event_type == "reminder",
            )
            .with_for_update()
        )
        if event is None:
            raise LookupError("Reminder not found")
        if event.version != version:
            raise ValueError("Reminder changed")
        if event.audio_status == "unavailable":
            event.audio_status = "pending"
            await session.commit()
            return "pending", True
        return event.audio_status, False

    async def pending(self) -> list[tuple[str, uuid.UUID]]:
        stale = datetime.now(UTC) - timedelta(minutes=self.settings.butler_processing_stale_minutes)
        found: list[tuple[str, uuid.UUID]] = []
        async with self.sessions() as session:
            for kind, model in (("request", ButlerRequestModel), ("event", DailyEventModel)):
                rows = await session.scalars(
                    select(model.id).where(
                        or_(
                            model.audio_status == "pending",
                            (model.audio_status == "processing") & (model.updated_at < stale),
                        ),
                        *(
                            (model.status == "completed",)
                            if kind == "request"
                            else (
                                model.event_type.in_(
                                    ("morning_brief", "good_night_summary", "reminder")
                                ),
                                model.deleted_at.is_(None),
                                or_(model.speak_aloud.is_(True), model.event_type == "reminder"),
                                model.status == "planned",
                            )
                        ),
                    )
                )
                found.extend((kind, row) for row in rows)
        return found

    async def generate(self, kind: str, owner_id: uuid.UUID) -> None:
        model = self._model(kind)
        async with self.sessions() as session, session.begin():
            row = await session.get(model, owner_id, with_for_update=True)
            stale = datetime.now(UTC) - timedelta(
                minutes=self.settings.butler_processing_stale_minutes
            )
            if row is None or (
                row.audio_status != "pending"
                and not (row.audio_status == "processing" and row.updated_at < stale)
            ):
                return
            if kind == "request" and row.status != "completed":
                return
            if kind == "event" and (
                row.deleted_at
                or (not row.speak_aloud and row.event_type != "reminder")
                or row.status != "planned"
                or row.event_type not in ("morning_brief", "good_night_summary", "reminder")
            ):
                return
            text = (
                row.response_text
                if kind == "request"
                else (
                    (row.content or row.description or row.title)
                    if row.event_type == "reminder"
                    else row.content
                )
            )
            user_id = row.user_id
            version = row.version if kind == "event" else None
            row.audio_status = "processing"
        path: Path | None = None
        charged = False
        cancelled = False
        try:
            if not self.settings.butler_voice_enabled or not text:
                return
            provider, charged = await self._choose(model, owner_id, user_id)
            if provider is None:
                return
            try:
                speech = await provider.synthesize(text=text, request_id=owner_id)
                path = await self._store(kind, owner_id, speech.audio, speech.mime_type, version)
            except Exception:
                logger.warning("Speech failed kind=%s id=%s", kind, owner_id, exc_info=True)
                if charged:
                    await self._refund(model, owner_id, user_id)
                    charged = False
                if (
                    provider is self.openai
                    and self.open_source is not None
                    and provider is not self.open_source
                ):
                    try:
                        speech = await self.open_source.synthesize(text=text, request_id=owner_id)
                        path = await self._store(
                            kind, owner_id, speech.audio, speech.mime_type, version
                        )
                    except Exception:
                        logger.warning(
                            "Speech fallback failed kind=%s id=%s", kind, owner_id, exc_info=True
                        )
        except asyncio.CancelledError:
            cancelled = True
            if charged:
                await self._refund(model, owner_id, user_id)
            raise
        except Exception:
            logger.exception("Speech unavailable kind=%s id=%s", kind, owner_id)
            if charged:
                await self._refund(model, owner_id, user_id)
        finally:
            async with self.sessions() as session, session.begin():
                row = await session.get(model, owner_id, with_for_update=True)
                if row is not None:
                    if kind == "event" and row.version != version:
                        if path:
                            await asyncio.to_thread(path.unlink, missing_ok=True)
                    else:
                        row.audio_status = (
                            "pending" if cancelled else "ready" if path else "unavailable"
                        )
                        row.response_audio_path = str(path) if path else None
                        row.response_audio_mime_type = "audio/ogg" if path else None
                        row.response_audio_delete_after = (
                            datetime.now(UTC)
                            + timedelta(days=self.settings.butler_response_audio_retention_days)
                            if path
                            else None
                        )

    async def _choose(self, model, owner_id, user_id):
        async with self.sessions() as session, session.begin():
            row = await session.get(model, owner_id, with_for_update=True)
            user = await session.get(UserModel, user_id, with_for_update=True)
            if row is None or user is None:
                return None, False
            if row.tts_credit_charged:
                return self.openai, True
            if user.tts_method != "OPENAI":
                return self.open_source, False
            cost = self.settings.butler_openai_tts_credit_cost
            if cost and user.credits < cost:
                return self.open_source, False
            if self.openai is None:
                return self.open_source, False
            if cost:
                user.credits -= cost
                row.tts_credit_charged = True
            return self.openai, bool(cost)

    async def _refund(self, model, owner_id, user_id):
        cost = self.settings.butler_openai_tts_credit_cost
        async with self.sessions() as session, session.begin():
            row = await session.get(model, owner_id, with_for_update=True)
            if row is not None and row.tts_credit_charged:
                await session.execute(
                    update(UserModel)
                    .where(UserModel.id == user_id)
                    .values(credits=UserModel.credits + cost)
                )
                row.tts_credit_charged = False

    async def _store(
        self, kind: str, owner_id: uuid.UUID, wav: bytes, mime: str, version: int | None
    ) -> Path:
        if mime != "audio/wav" or len(wav) < 12 or wav[:4] != b"RIFF" or wav[8:12] != b"WAVE":
            raise ValueError("Speech provider returned invalid WAV")
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
        data, stderr = await process.communicate(wav)
        if (
            process.returncode
            or len(data) < 32
            or data[:4] != b"OggS"
            or b"OpusHead" not in data[:256]
        ):
            raise ValueError(
                f"Speech WAV to Ogg/Opus failed: {stderr.decode(errors='replace')[:200]}"
            )
        filename = f"{owner_id}-{version}.ogg" if version is not None else f"{owner_id}.ogg"
        path = (
            Path(self.settings.butler_audio_root)
            / ("responses" if kind == "request" else "events")
            / filename
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(path.write_bytes, data)
        return path

    async def cleanup_audio(self) -> None:
        async with self.sessions() as session, session.begin():
            rows = await session.scalars(
                select(DailyEventModel).where(
                    DailyEventModel.response_audio_delete_after <= datetime.now(UTC)
                )
            )
            for row in rows:
                if row.response_audio_path:
                    try:
                        await asyncio.to_thread(
                            Path(row.response_audio_path).unlink, missing_ok=True
                        )
                    except OSError:
                        logger.warning("Event audio cleanup failed id=%s", row.id, exc_info=True)
                        continue
                row.response_audio_path = None
                row.response_audio_mime_type = None
                row.response_audio_delete_after = None
                row.audio_status = "unavailable"
        directory = Path(self.settings.butler_audio_root) / "events"
        cutoff = (
            datetime.now(UTC).timestamp()
            - 86400 * self.settings.butler_response_audio_retention_days
        )
        if directory.exists():
            for path in directory.glob("*.ogg"):
                try:
                    if path.stat().st_mtime < cutoff:
                        await asyncio.to_thread(path.unlink, missing_ok=True)
                except OSError:
                    logger.warning(
                        "Orphaned event audio cleanup failed path=%s", path, exc_info=True
                    )
