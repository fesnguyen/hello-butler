from __future__ import annotations

import uuid
from datetime import datetime
from typing import cast
from zoneinfo import ZoneInfo

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.actions import DailyEventActions, UserContextActions
from app.application.butler.context import ButlerContextLoader
from app.application.butler.contracts import (
    ButlerAIProvider,
    ButlerError,
    ButlerResult,
    InteractionMode,
)
from app.application.butler.graph import ButlerGraph
from app.application.butler.history import ButlerHistoryWriter
from app.application.butler.state import ButlerState
from app.core.config import Settings


class ButlerService:
    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        ai_provider: ButlerAIProvider,
    ) -> None:
        self._settings = settings
        self._graph = ButlerGraph(
            ai_provider=ai_provider,
            context_loader=ButlerContextLoader(settings, session_factory),
            event_actions=DailyEventActions(session_factory),
            context_actions=UserContextActions(session_factory),
            history_writer=ButlerHistoryWriter(session_factory),
        ).compiled

    async def handle(
        self, *, user_id: uuid.UUID, interaction_mode: InteractionMode, message: str
    ) -> ButlerResult:
        normalized = message.strip()
        if not normalized:
            return ButlerResult(
                response="What would you like me to help with?", requires_follow_up=True
            )

        timezone = self._settings.butler_default_timezone
        now = datetime.now(ZoneInfo(timezone))
        state: ButlerState = {
            "user_id": user_id,
            "interaction_mode": interaction_mode,
            "message": normalized,
            "now": now,
            "timezone": timezone,
            "today": now.date(),
        }
        result = cast(ButlerState, await self._graph.ainvoke(state))
        if (butler_result := result.get("result")) is None:
            raise ButlerError("Butler graph completed without a result")
        return butler_result
