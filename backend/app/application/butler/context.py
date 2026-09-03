from __future__ import annotations

import uuid
from collections.abc import Sequence
from datetime import date

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.contracts import (
    ButlerContext,
    ContextEntry,
    ContextEvent,
    ContextMessage,
    ContextPlan,
)
from app.core.config import Settings
from app.infrastructure.db.models import (
    ConversationMessageModel,
    DailyEventModel,
    DailyPlanModel,
    UserContextEntryModel,
)


class ButlerContextLoader:
    def __init__(
        self, settings: Settings, session_factory: async_sessionmaker[AsyncSession]
    ) -> None:
        self._settings = settings
        self._session_factory = session_factory

    async def load(self, user_id: uuid.UUID, today: date) -> ButlerContext:
        async with self._session_factory() as session:
            history = await self._latest_messages(session, user_id)
            user_context = await self._active_context(session, user_id, today)
            daily_plan = await self._daily_plan(session, user_id, today)
            events = await self._events_for_day(session, user_id, today)

        return ButlerContext(
            conversation_history=[
                ContextMessage(role=row.role, content=row.content) for row in reversed(history)
            ],
            user_context=[
                ContextEntry(
                    id=row.id,
                    context_type=row.context_type,
                    content=row.content,
                    starts_on=row.starts_on,
                    ends_on=row.ends_on,
                )
                for row in user_context
            ],
            daily_plan=(
                ContextPlan(
                    id=daily_plan.id, plan_date=daily_plan.plan_date, status=daily_plan.status
                )
                if daily_plan
                else None
            ),
            relevant_events=[self._event(row) for row in events],
        )

    async def _latest_messages(
        self, session: AsyncSession, user_id: uuid.UUID
    ) -> Sequence[ConversationMessageModel]:
        result = await session.execute(
            select(ConversationMessageModel)
            .where(ConversationMessageModel.user_id == user_id)
            .order_by(ConversationMessageModel.created_at.desc())
            .limit(self._settings.butler_history_limit)
        )
        return result.scalars().all()

    async def _active_context(
        self, session: AsyncSession, user_id: uuid.UUID, today: date
    ) -> Sequence[UserContextEntryModel]:
        result = await session.execute(
            select(UserContextEntryModel)
            .where(
                UserContextEntryModel.user_id == user_id,
                UserContextEntryModel.deleted_at.is_(None),
                or_(
                    UserContextEntryModel.starts_on.is_(None),
                    UserContextEntryModel.starts_on <= today,
                ),
                or_(
                    UserContextEntryModel.ends_on.is_(None),
                    UserContextEntryModel.ends_on >= today,
                ),
            )
            .order_by(UserContextEntryModel.created_at.desc())
            .limit(self._settings.butler_user_context_limit)
        )
        return result.scalars().all()

    async def _daily_plan(
        self, session: AsyncSession, user_id: uuid.UUID, plan_date: date
    ) -> DailyPlanModel | None:
        result = await session.execute(
            select(DailyPlanModel).where(
                DailyPlanModel.user_id == user_id,
                DailyPlanModel.plan_date == plan_date,
                DailyPlanModel.deleted_at.is_(None),
            )
        )
        return result.scalar_one_or_none()

    async def _events_for_day(
        self, session: AsyncSession, user_id: uuid.UUID, event_date: date
    ) -> Sequence[DailyEventModel]:
        result = await session.execute(
            select(DailyEventModel)
            .where(
                DailyEventModel.user_id == user_id,
                DailyEventModel.event_date == event_date,
                DailyEventModel.deleted_at.is_(None),
            )
            .order_by(
                DailyEventModel.start_time.is_(None),
                DailyEventModel.start_time,
                DailyEventModel.sort_order,
            )
            .limit(self._settings.butler_event_limit)
        )
        return result.scalars().all()

    @staticmethod
    def _event(row: DailyEventModel) -> ContextEvent:
        return ContextEvent(
            id=row.id,
            title=row.title,
            description=row.description,
            event_type=row.event_type,
            status=row.status,
            event_date=row.event_date,
            start_time=row.start_time,
            end_time=row.end_time,
            duration_minutes=row.duration_minutes,
        )
