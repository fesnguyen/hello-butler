from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta

from sqlalchemy import select

from app.application.planning.service import DayPlanningService
from app.core.config import Settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider
from app.infrastructure.db.models import UserModel

logger = logging.getLogger(__name__)


class NightlyPlanningScheduler:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._service = DayPlanningService(
            settings,
            AsyncSessionLocal,
            OpenAIButlerProvider(api_key=settings.openai_api_key, model=settings.openai_model),
        )

    async def run_forever(self) -> None:
        while True:
            now = datetime.now(self._settings.timezone)
            next_run = datetime.combine(now.date(), self._settings.nightly_planning_time, self._settings.timezone)
            if next_run <= now:
                next_run += timedelta(days=1)
            await asyncio.sleep((next_run - now).total_seconds())
            await self.prepare_tomorrow()

    async def prepare_tomorrow(self) -> None:
        target_date = datetime.now(self._settings.timezone).date() + timedelta(days=1)
        async with AsyncSessionLocal() as session:
            user_ids = list((await session.execute(select(UserModel.id))).scalars())

        for user_id in user_ids:
            try:
                await self._service.prepare(user_id, target_date)
            except asyncio.CancelledError:
                raise
            except Exception:
                # One user's provider/data failure must not prevent tomorrow from being prepared for others.
                logger.exception("Nightly planning failed for user %s on %s", user_id, target_date)
