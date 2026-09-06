from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import ButlerResult, ChangedEntity
from app.application.butler.state import ButlerState, ButlerStateUpdate, decision_from
from app.infrastructure.db.models import UserContextEntryModel


class UserContextActions:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def remember(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        if not decision.context_content:
            return {
                "result": ButlerResult(
                    response="What would you like me to remember?", requires_follow_up=True
                )
            }

        async with self._session_factory() as session, session.begin():
            entry = UserContextEntryModel(
                id=uuid.uuid4(),
                user_id=state["user_id"],
                context_type=decision.context_type or "reference",
                content=decision.context_content,
                starts_on=decision.context_starts_on,
                ends_on=decision.context_ends_on,
            )
            session.add(entry)
            changed = ChangedEntity(type="user_context", id=entry.id)

        return {
            "result": ButlerResult(
                response="Got it. I will remember that.", changed_entities=[changed]
            )
        }
