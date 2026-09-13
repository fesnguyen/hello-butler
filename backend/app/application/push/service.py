from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from typing import Protocol

from sqlalchemy import delete, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.infrastructure.db.models import PushDeviceModel

logger = logging.getLogger(__name__)


class PushProvider(Protocol):
    async def send_data(self, tokens: Sequence[str], data: dict[str, str]) -> set[str]: ...


class PushService:
    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        provider: PushProvider,
    ) -> None:
        self._session_factory = session_factory
        self._provider = provider

    async def register(self, user_id: uuid.UUID, token: str) -> None:
        async with self._session_factory() as session, session.begin():
            await session.execute(
                insert(PushDeviceModel)
                .values(
                    id=uuid.uuid4(), user_id=user_id, registration_token=token, platform="android"
                )
                .on_conflict_do_update(
                    index_elements=["registration_token"],
                    set_={"user_id": user_id, "platform": "android"},
                )
            )

    async def unregister(self, user_id: uuid.UUID, token: str) -> None:
        async with self._session_factory() as session, session.begin():
            await session.execute(
                delete(PushDeviceModel).where(
                    PushDeviceModel.user_id == user_id,
                    PushDeviceModel.registration_token == token,
                )
            )

    async def daily_plan_changed(self, user_id: uuid.UUID) -> None:
        await self._send(user_id, {"type": "daily_plan_changed"})

    async def butler_request_state(
        self, user_id: uuid.UUID, event_type: str, request_id: uuid.UUID
    ) -> None:
        await self._send(user_id, {"type": event_type, "request_id": str(request_id)})

    async def _send(self, user_id: uuid.UUID, data: dict[str, str]) -> None:
        try:
            async with self._session_factory() as session:
                tokens = list(
                    (
                        await session.execute(
                            select(PushDeviceModel.registration_token).where(
                                PushDeviceModel.user_id == user_id
                            )
                        )
                    ).scalars()
                )
            if not tokens:
                return
            invalid = await self._provider.send_data(tokens, data)
            if invalid:
                async with self._session_factory() as session, session.begin():
                    await session.execute(
                        delete(PushDeviceModel).where(
                            PushDeviceModel.registration_token.in_(invalid)
                        )
                    )
        except Exception:
            logger.exception("Push hint failed for user %s", user_id)
            return
