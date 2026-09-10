from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import Session, UOWTransaction

from app.application.push.service import PushService
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel

logger = logging.getLogger(__name__)


class DailyPlanChanges:
    """Publish a hint only after a canonical plan transaction has committed."""

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
