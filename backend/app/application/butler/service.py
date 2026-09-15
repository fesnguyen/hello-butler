from __future__ import annotations

import uuid
from datetime import datetime
from pathlib import Path
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
from app.application.push.changes import DailyPlanChanges
from app.core.config import Settings


class ButlerService:
    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        ai_provider: ButlerAIProvider,
        changes: DailyPlanChanges,
    ) -> None:
        self._settings = settings
        self._graph = ButlerGraph(
            ai_provider=ai_provider,
            context_loader=ButlerContextLoader(settings, session_factory),
            event_actions=DailyEventActions(changes),
            context_actions=UserContextActions(session_factory),
            history_writer=ButlerHistoryWriter(session_factory),
        ).compiled

    async def handle(
        self,
        *,
        user_id: uuid.UUID,
        interaction_mode: InteractionMode,
        message: str | None = None,
        audio_path: Path | None = None,
        audio_mime_type: str | None = None,
        request_id: uuid.UUID | None = None,
    ) -> ButlerResult:
        has_text = message is not None and bool(message.strip())
        has_audio = audio_path is not None
        if has_text == has_audio:
            raise ButlerError("Butler requires exactly one text or audio input")
        if has_audio and not audio_mime_type:
            raise ButlerError("Butler audio input requires a MIME type")

        timezone = self._settings.butler_default_timezone
        now = datetime.now(ZoneInfo(timezone))
        state: ButlerState = {
            "user_id": user_id,
            "interaction_mode": interaction_mode,
            "message": message or "",
            "audio_path": audio_path,
            "audio_mime_type": audio_mime_type,
            "now": now,
            "timezone": timezone,
            "today": now.date(),
        }
        if request_id is not None:
            state["request_id"] = request_id
        final_state = cast(ButlerState, await self._graph.ainvoke(state))
        if (result := final_state.get("result")) is None:
            raise ButlerError("Butler graph completed without a result")
        user_message_text = final_state["message"]
        if not user_message_text.strip():
            raise ButlerError("Butler graph completed without canonical user text")
        return result.model_copy(update={"user_message_text": user_message_text})
