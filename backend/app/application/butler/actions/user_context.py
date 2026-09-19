from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import (
    ButlerMutationRejectedError,
    ButlerResult,
    ChangedEntity,
)
from app.application.butler.idempotency import prior_action_result, save_action_result
from app.application.butler.state import (
    ButlerState,
    ButlerStateUpdate,
    canonical_response_from,
    decision_from,
)
from app.infrastructure.db.models import UserContextEntryModel


class UserContextActions:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def remember(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        if not decision.context_content:
            raise ButlerMutationRejectedError("User Context creation requires content")

        async with self._session_factory() as session, session.begin():
            if prior := await prior_action_result(session, state):
                return {"result": prior}
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
            result = ButlerResult(
                response=canonical_response_from(state), changed_entities=[changed]
            )
            save_action_result(session, state, result)

        return {"result": result}

    async def update(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        if decision.target_context_id is None:
            raise ButlerMutationRejectedError("User Context update requires a target id")

        async with self._session_factory() as session, session.begin():
            if prior := await prior_action_result(session, state):
                return {"result": prior}
            entry = await session.get(
                UserContextEntryModel, decision.target_context_id, with_for_update=True
            )
            if entry is None or entry.user_id != state["user_id"] or entry.deleted_at is not None:
                raise ButlerMutationRejectedError("User Context target could not be resolved")

            changed = False
            if decision.context_content is not None:
                entry.content = decision.context_content
                changed = True
            if decision.context_type is not None:
                entry.context_type = decision.context_type
                changed = True
            if decision.context_starts_on is not None:
                entry.starts_on = decision.context_starts_on
                changed = True
            if decision.context_ends_on is not None:
                entry.ends_on = decision.context_ends_on
                changed = True
            if not changed:
                raise ButlerMutationRejectedError("User Context update contains no changes")

            entity = ChangedEntity(type="user_context", id=entry.id)
            result = ButlerResult(
                response=canonical_response_from(state), changed_entities=[entity]
            )
            save_action_result(session, state, result)

        return {"result": result}
