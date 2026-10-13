"""Shared, durable speech generation for conversations and scheduled summaries."""

from __future__ import annotations

import asyncio
import logging
import os
import re
import uuid
from datetime import UTC, datetime, time, timedelta
from pathlib import Path

from sqlalchemy import or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import ButlerVoiceProvider
from app.core.config import Settings
from app.infrastructure.db.models import ButlerRequestModel, DailyEventModel, UserModel

logger = logging.getLogger(__name__)


def event_speech_text(event: DailyEventModel) -> str:
    return next(
        (
            text.strip()
            for text in (event.content, event.description, event.title)
            if text and text.strip()
        ),
        "",
    )


def retime_event_speech(event: DailyEventModel, previous: time | None) -> None:
    if (
        previous is None
        or event.start_time is None
        or event.event_type in ("morning_brief", "good_night_summary")
    ):
        return
    field = next(
        (
            name
            for name in ("content", "description", "title")
            if getattr(event, name) and getattr(event, name).strip()
        ),
        None,
    )
    if field is None:
        return
    hour, marker = previous.hour % 12 or 12, "AM" if previous.hour < 12 else "PM"
    minute = f":{previous.minute:02d}" if previous.minute else "(?::00)?"
    pattern = re.compile(
        rf"(?<![\w:])(?:{re.escape(previous.strftime('%H:%M'))}|{hour}{minute}\s*{marker})(?![\w:])",
        re.I,
    )

    def replace(match: re.Match[str]) -> str:
        new = event.start_time
        assert new is not None
        if "am" not in match[0].lower() and "pm" not in match[0].lower():
            return new.strftime("%H:%M")
        suffix = "AM" if new.hour < 12 else "PM"
        if match[0].islower():
            suffix = suffix.lower()
        minutes = f":{new.minute:02d}" if new.minute or ":" in match[0] else ""
        space = " " if " " in match[0] else ""
        return f"{new.hour % 12 or 12}{minutes}{space}{suffix}"

    setattr(event, field, pattern.sub(replace, getattr(event, field)))


def event_speech_eligible(event: DailyEventModel) -> bool:
    return (
        not event.deleted_at
        and (event.status or "planned") == "planned"
        and (event.speak_aloud or event.event_type == "reminder")
        and bool(event_speech_text(event))
    )


def invalidate_event_speech(event: DailyEventModel) -> None:
    event.audio_status = "pending" if event_speech_eligible(event) else "unavailable"
    event.response_audio_path = None
    event.response_audio_mime_type = None
    event.response_audio_delete_after = None
    event.tts_credit_charged = False


def valid_event_audio(path: Path | None) -> bool:
    if path is None:
        return False
    try:
        with path.open("rb") as audio:
            header = audio.read(256)
        return header[:4] == b"OggS" and b"OpusHead" in header
    except OSError:
        return False


def publish_audio(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(f"{path.name}.{uuid.uuid4()}.part")
    try:
        partial.write_bytes(data)
        os.replace(partial, path)
    finally:
        partial.unlink(missing_ok=True)


def rebind_event_speech(event: DailyEventModel, previous_version: int) -> bool:
    """Only a canonical unchanged-text mutation may carry bytes into its new version."""
    source = Path(event.response_audio_path) if event.response_audio_path else None
    if source is None or source.name != f"{event.id}-{previous_version}.ogg":
        return False
    if not valid_event_audio(source):
        return False
    target = source.with_name(f"{event.id}-{event.version}.ogg")
    try:
        publish_audio(target, source.read_bytes())
    except OSError:
        logger.warning("Could not rebind event speech id=%s", event.id, exc_info=True)
        return False
    event.response_audio_path = str(target)
    return True


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
    async def prepare_event(
        session: AsyncSession, user_id: uuid.UUID, owner_id: uuid.UUID, version: int
    ) -> tuple[str, bool]:
        """Prepare canonical event speech before its alarm; reuse unchanged narration."""
        event = await session.scalar(
            select(DailyEventModel)
            .where(
                DailyEventModel.id == owner_id,
                DailyEventModel.user_id == user_id,
            )
            .with_for_update()
        )
        if event is None:
            raise LookupError("event_not_found")
        if event.deleted_at:
            raise FileNotFoundError("event_deleted")
        if event.version != version:
            raise ValueError("event_version_stale")
        if not event_speech_eligible(event):
            raise TypeError("event_not_speech_enabled")
        path = Path(event.response_audio_path) if event.response_audio_path else None
        if (
            event.audio_status == "ready"
            and path is not None
            and path.name == f"{owner_id}-{version}.ogg"
            and valid_event_audio(path)
        ):
            return "ready", False
        if event.audio_status in ("unavailable", "ready"):
            event.audio_status = "pending"
            event.response_audio_path = None
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
                                DailyEventModel.deleted_at.is_(None),
                                or_(
                                    DailyEventModel.speak_aloud.is_(True),
                                    DailyEventModel.event_type == "reminder",
                                ),
                                DailyEventModel.status == "planned",
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
            if isinstance(row, DailyEventModel) and not event_speech_eligible(row):
                return
            text = (
                row.response_text if isinstance(row, ButlerRequestModel) else event_speech_text(row)
            )
            user_id = row.user_id
            version = row.version if isinstance(row, DailyEventModel) else None
            row.audio_status = "processing"
        path: Path | None = None
        charged = False
        cancelled = False
        try:
            if not self.settings.butler_voice_enabled or not text:
                return
            provider, charged = await self._choose(model, owner_id, user_id, version)
            if provider is None:
                return
            try:
                speech = await provider.synthesize(text=text, request_id=owner_id)
                path = await self._store(kind, owner_id, speech.audio, speech.mime_type, version)
            except Exception:
                logger.warning("Speech failed kind=%s id=%s", kind, owner_id, exc_info=True)
                if charged:
                    await self._refund(model, owner_id, user_id, version)
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
                await self._refund(model, owner_id, user_id, version)
                charged = False
            raise
        except Exception:
            logger.exception("Speech unavailable kind=%s id=%s", kind, owner_id)
            if charged:
                await self._refund(model, owner_id, user_id, version)
                charged = False
        finally:
            obsolete = False
            async with self.sessions() as session, session.begin():
                row = await session.get(model, owner_id, with_for_update=True)
                if row is not None:
                    if isinstance(row, DailyEventModel) and row.version != version:
                        obsolete = True
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
            if obsolete and charged:
                await self._refund(model, owner_id, user_id, version)

    async def _choose(self, model, owner_id, user_id, version=None):
        async with self.sessions() as session, session.begin():
            row = await session.get(model, owner_id, with_for_update=True)
            user = await session.get(UserModel, user_id, with_for_update=True)
            if row is None or user is None:
                return None, False
            if version is not None and row.version != version:
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

    async def _refund(self, model, owner_id, user_id, version=None):
        cost = self.settings.butler_openai_tts_credit_cost
        async with self.sessions() as session, session.begin():
            row = await session.get(model, owner_id, with_for_update=True)
            obsolete = row is not None and version is not None and row.version != version
            if row is not None and (row.tts_credit_charged or obsolete):
                await session.execute(
                    update(UserModel)
                    .where(UserModel.id == user_id)
                    .values(credits=UserModel.credits + cost)
                )
                if not obsolete:
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
        await asyncio.to_thread(publish_audio, path, data)
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
