from __future__ import annotations

import uuid
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker


class UserPlanningLock:
    """PostgreSQL session lock serializing AI planning workflows for one user."""

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    @asynccontextmanager
    async def hold(self, user_id: uuid.UUID) -> AsyncGenerator[None]:
        key = int.from_bytes(user_id.bytes[:8], byteorder="big", signed=True)
        async with self._session_factory() as session:
            await session.execute(text("SELECT pg_advisory_lock(:key)"), {"key": key})
            try:
                yield
            finally:
                await session.execute(text("SELECT pg_advisory_unlock(:key)"), {"key": key})
