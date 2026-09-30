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
from app.application.upcoming import UpcomingEventMutation, UpcomingEventService
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
                is_actionable=decision.context_is_actionable or False,
                title=decision.context_title,
                start_time=decision.context_start_time,
                end_time=decision.context_end_time,
                recurrence=decision.context_recurrence,
                recurrence_days=decision.context_recurrence_days or [],
            )
            self._validate_saved_context(entry)
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
            if decision.context_is_actionable is not None:
                entry.is_actionable = decision.context_is_actionable
                changed = True
            if decision.context_title is not None:
                entry.title = decision.context_title
                changed = True
            if decision.context_start_time is not None:
                entry.start_time = decision.context_start_time
                changed = True
            if decision.context_end_time is not None:
                entry.end_time = decision.context_end_time
                changed = True
            if decision.context_recurrence is not None:
                entry.recurrence = decision.context_recurrence
                changed = True
            if decision.context_recurrence_days is not None:
                entry.recurrence_days = decision.context_recurrence_days
                changed = True
            if not changed:
                raise ButlerMutationRejectedError("User Context update contains no changes")
            self._validate_saved_context(entry)
            entry.version += 1

            entity = ChangedEntity(type="user_context", id=entry.id)
            result = ButlerResult(
                response=canonical_response_from(state), changed_entities=[entity]
            )
            save_action_result(session, state, result)

        return {"result": result}

    @staticmethod
    def _validate_saved_context(entry: UserContextEntryModel) -> None:
        if entry.context_type not in ("note", "preference"):
            return
        if not entry.content.strip():
            raise ButlerMutationRejectedError("Saved context requires a description")
        if (
            entry.is_actionable
            or entry.starts_on is not None
            or entry.ends_on is not None
            or entry.recurrence is not None
            or entry.start_time is not None
            or entry.end_time is not None
        ):
            raise ButlerMutationRejectedError(
                "Notes and preferences cannot carry planning schedules"
            )
        entry.content = entry.content.strip()

    async def mutate_upcoming(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        if (
            decision.target_context_id is None
            or decision.target_context_version is None
            or decision.upcoming_action is None
            or decision.upcoming_scope is None
        ):
            raise ButlerMutationRejectedError("Upcoming Event mutation is incomplete")
        mutation = UpcomingEventMutation(
            action=decision.upcoming_action,
            scope=decision.upcoming_scope,
            base_version=decision.target_context_version,
            occurrence_date=decision.upcoming_occurrence_date,
            title=decision.context_title,
            starts_on=decision.context_starts_on,
            ends_on=decision.context_ends_on,
            start_time=decision.context_start_time,
            end_time=decision.context_end_time,
        )
        async with self._session_factory() as session, session.begin():
            if prior := await prior_action_result(session, state):
                return {"result": prior}
            await UpcomingEventService().mutate(
                session,
                state["user_id"],
                decision.target_context_id,
                mutation,
                state["today"],
            )
            entity = ChangedEntity(type="user_context", id=decision.target_context_id)
            result = ButlerResult(
                response=canonical_response_from(state), changed_entities=[entity]
            )
            save_action_result(session, state, result)
        return {"result": result}
