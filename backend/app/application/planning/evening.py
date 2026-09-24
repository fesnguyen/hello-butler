from __future__ import annotations

import uuid
from datetime import date, timedelta

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.planning.contracts import (
    DayPlanningAIProvider,
    EveningPreparationResult,
    GoodNightEvent,
    GoodNightSummaryInput,
    PlanningContextEntry,
)
from app.application.planning.lock import UserPlanningLock
from app.application.planning.service import DayPlanningService
from app.application.push.changes import DailyPlanChanges
from app.core.config import Settings
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel, UserContextEntryModel


class EveningPreparationService:
    def __init__(
        self,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        ai_provider: DayPlanningAIProvider,
        changes: DailyPlanChanges,
    ) -> None:
        self._settings = settings
        self._session_factory = session_factory
        self._ai = ai_provider
        self._changes = changes
        self._planner = DayPlanningService(settings, session_factory, ai_provider, changes)
        self._lock = UserPlanningLock(session_factory)

    async def prepare(self, user_id: uuid.UUID, summary_date: date) -> EveningPreparationResult:
        tomorrow = summary_date + timedelta(days=1)
        async with self._lock.hold(user_id):
            summary_input, protected_summary = await self._load_summary_input(
                user_id, summary_date, tomorrow
            )
            summary_content = (
                protected_summary
                or (await self._ai.compose_good_night_summary(summary_input)).content
            )
            tomorrow_plan = await self._planner.prepare(user_id, tomorrow)
            if protected_summary is None:
                await self._persist_summary(user_id, summary_date, summary_content)
        return EveningPreparationResult(
            summary_date=summary_date,
            tomorrow_date=tomorrow,
            good_night_summary=summary_content,
            tomorrow_plan_id=tomorrow_plan.plan_id,
        )

    async def _load_summary_input(
        self, user_id: uuid.UUID, summary_date: date, tomorrow: date
    ) -> tuple[GoodNightSummaryInput, str | None]:
        async with self._session_factory() as session:
            contexts = list(
                (
                    await session.execute(
                        select(UserContextEntryModel)
                        .where(
                            UserContextEntryModel.user_id == user_id,
                            UserContextEntryModel.deleted_at.is_(None),
                            or_(
                                UserContextEntryModel.starts_on.is_(None),
                                UserContextEntryModel.starts_on <= tomorrow,
                            ),
                            or_(
                                UserContextEntryModel.ends_on.is_(None),
                                UserContextEntryModel.ends_on >= summary_date,
                            ),
                        )
                        .order_by(UserContextEntryModel.created_at.desc())
                        .limit(self._settings.butler_user_context_limit)
                    )
                ).scalars()
            )
            events = list(
                (
                    await session.execute(
                        select(DailyEventModel)
                        .where(
                            DailyEventModel.user_id == user_id,
                            DailyEventModel.event_date == summary_date,
                            DailyEventModel.deleted_at.is_(None),
                        )
                        .order_by(
                            DailyEventModel.start_time.is_(None),
                            DailyEventModel.start_time,
                            DailyEventModel.sort_order,
                        )
                    )
                ).scalars()
            )
        protected = next(
            (
                event.content
                for event in events
                if event.event_type == "good_night_summary"
                and event.origin != "planner"
                and event.content
            ),
            None,
        )
        relevant = [event for event in events if event.event_type != "good_night_summary"]
        return (
            GoodNightSummaryInput(
                summary_date=summary_date,
                tomorrow_date=tomorrow,
                timezone=self._settings.butler_default_timezone,
                user_context=[
                    PlanningContextEntry(
                        context_type=row.context_type,
                        content=row.content,
                        starts_on=row.starts_on,
                        ends_on=row.ends_on,
                    )
                    for row in contexts
                ],
                events=[
                    GoodNightEvent(
                        title=row.title,
                        event_type=row.event_type,
                        status=row.status,
                        start_time=row.start_time,
                        description=row.description,
                    )
                    for row in relevant
                ],
            ),
            protected,
        )

    async def _persist_summary(self, user_id: uuid.UUID, summary_date: date, content: str) -> None:
        async with self._changes.transaction(user_id) as session:
            plan = (
                await session.execute(
                    select(DailyPlanModel)
                    .where(
                        DailyPlanModel.user_id == user_id,
                        DailyPlanModel.plan_date == summary_date,
                    )
                    .with_for_update()
                )
            ).scalar_one_or_none()
            if plan is None:
                plan = DailyPlanModel(
                    id=uuid.uuid4(), user_id=user_id, plan_date=summary_date, status="completed"
                )
                session.add(plan)
                await session.flush()
            else:
                plan.deleted_at = None
            summary = (
                await session.execute(
                    select(DailyEventModel).where(
                        DailyEventModel.user_id == user_id,
                        DailyEventModel.event_date == summary_date,
                        DailyEventModel.planner_key == "good_night_summary",
                    )
                )
            ).scalar_one_or_none()
            if summary is None:
                summary = DailyEventModel(
                    id=uuid.uuid4(),
                    user_id=user_id,
                    daily_plan_id=plan.id,
                    event_date=summary_date,
                    title="Good Night Summary",
                    event_type="good_night_summary",
                    status="planned",
                    version=1,
                    origin="planner",
                    planner_key="good_night_summary",
                )
                summary.audio_status = "pending"
            else:
                changed = (
                    summary.content != content
                    or summary.start_time != self._settings.good_night_summary_time
                )
                if changed:
                    summary.version += 1
                    summary.audio_status = "pending"
                    summary.response_audio_path = None
                    summary.response_audio_mime_type = None
                    summary.response_audio_delete_after = None
            summary.daily_plan_id = plan.id
            summary.description = "Butler's reflection on your actual day"
            summary.start_time = self._settings.good_night_summary_time
            summary.end_time = None
            summary.duration_minutes = None
            summary.scheduled_precision = "exact"
            summary.content = content
            summary.reminder_minutes_before = None
            summary.speak_aloud = True
            summary.sort_order = 10_000
            summary.deleted_at = None
            session.add(summary)
