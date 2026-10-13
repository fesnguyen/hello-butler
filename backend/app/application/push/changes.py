from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from sqlalchemy import event, inspect
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import Session, UOWTransaction

from app.application.push.service import PushService
from app.application.speech import (
    event_speech_eligible,
    event_speech_text,
    invalidate_event_speech,
    rebind_event_speech,
    retime_event_speech,
)
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel

logger = logging.getLogger(__name__)


class DailyPlanChanges:
    """Maintain event speech state and publish hints after canonical plan commits."""

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        push: PushService,
        timeout_seconds: float = 5,
    ) -> None:
        self._sessions = session_factory
        self._push = push
        self._timeout_seconds = timeout_seconds

    @asynccontextmanager
    async def transaction(self, user_id: uuid.UUID) -> AsyncGenerator[AsyncSession]:
        changed = False

        def before_flush(
            session: Session, flush_context: UOWTransaction, instances: object
        ) -> None:
            nonlocal changed
            for row in [*session.new, *session.dirty]:
                if not isinstance(row, DailyEventModel):
                    continue
                state = inspect(row)
                previous = {
                    name: list(state.attrs[name].history.deleted)[0]
                    if state.attrs[name].history.deleted
                    else getattr(row, name)
                    for name in ("content", "description", "title")
                }
                old_text = next(
                    (text.strip() for text in previous.values() if text and text.strip()), ""
                )
                times = list(state.attrs.start_time.history.deleted)
                if times and old_text == event_speech_text(row):
                    retime_event_speech(row, times[0])
                speech_changed = old_text != event_speech_text(row) or any(
                    state.attrs[name].history.has_changes()
                    for name in ("event_type", "speak_aloud")
                )
                version_changed = state.attrs.version.history.has_changes()
                if (
                    row in session.new
                    or speech_changed
                    or not event_speech_eligible(row)
                    or (version_changed and row.audio_status == "processing")
                ):
                    invalidate_event_speech(row)
                elif version_changed and row.audio_status == "ready":
                    versions = list(state.attrs.version.history.deleted)
                    if not versions or not rebind_event_speech(row, versions[0]):
                        invalidate_event_speech(row)
            changed |= any(
                isinstance(row, (DailyPlanModel, DailyEventModel))
                and row.user_id == user_id
                and (row in session.new or row in session.deleted or session.is_modified(row))
                for row in [*session.new, *session.dirty, *session.deleted]
            )

        async with self._sessions() as session:
            event.listen(session.sync_session, "before_flush", before_flush)
            try:
                async with session.begin():
                    yield session
            finally:
                event.remove(session.sync_session, "before_flush", before_flush)
        if changed:
            try:
                async with asyncio.timeout(self._timeout_seconds):
                    await self._push.daily_plan_changed(user_id)
            except Exception:
                # Includes token lookup/cleanup failures; the canonical commit already succeeded.
                logger.exception("Could not publish daily plan change for user %s", user_id)
