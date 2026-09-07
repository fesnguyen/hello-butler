from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta

from sqlalchemy import select

from app.application.planning.evening import EveningPreparationService
from app.application.push import PushService
from app.core.config import Settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider
from app.infrastructure.db.models import UserModel

logger = logging.getLogger(__name__)


class NightlyPlanningScheduler:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        provider = OpenAIButlerProvider(
            api_key=settings.openai_api_key, model=settings.openai_model
        )
        from app.infrastructure.push import FirebasePushProvider

        self._service = EveningPreparationService(
            settings,
            AsyncSessionLocal,
            provider,
            PushService(
                AsyncSessionLocal,
                FirebasePushProvider(
                    project_id=settings.firebase_project_id,
                    credentials_path=settings.firebase_credentials_path,
                ),
            ),
        )

    async def run_forever(self) -> None:
        while True:
            now = datetime.now(self._settings.timezone)
            next_run = datetime.combine(
                now.date(), self._settings.evening_preparation_time, self._settings.timezone
            )
            if next_run <= now:
                next_run += timedelta(days=1)
            await asyncio.sleep((next_run - now).total_seconds())
            await self.prepare_evening()

    async def prepare_evening(self) -> None:
        summary_date = datetime.now(self._settings.timezone).date()
        async with AsyncSessionLocal() as session:
            user_ids = list((await session.execute(select(UserModel.id))).scalars())

        for user_id in user_ids:
            try:
                await self._service.prepare(user_id, summary_date)
            except asyncio.CancelledError:
                raise
            except Exception:
                # One user's failure must not block preparation for every other user.
                logger.exception(
                    "Evening preparation failed for user %s on %s", user_id, summary_date
                )
