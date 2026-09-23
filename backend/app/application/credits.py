from __future__ import annotations

import uuid
from typing import Any, cast

from sqlalchemy import update
from sqlalchemy.engine import CursorResult
from sqlalchemy.ext.asyncio import AsyncSession

from app.infrastructure.db.models import UserModel


class InsufficientCreditsError(RuntimeError):
    pass


async def consume_credits(session: AsyncSession, user_id: uuid.UUID, cost: int) -> None:
    if not cost:
        return
    result = cast(
        CursorResult[Any],
        await session.execute(
            update(UserModel)
            .where(UserModel.id == user_id, UserModel.credits >= cost)
            .values(credits=UserModel.credits - cost)
        ),
    )
    if result.rowcount != 1:
        raise InsufficientCreditsError("Insufficient credits for paid Butler AI")
