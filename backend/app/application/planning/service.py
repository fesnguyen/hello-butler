from __future__ import annotations

import uuid
from collections.abc import Sequence
from datetime import date, time

from sqlalchemy import or_, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.planning.contracts import (
    DayPlanningAIProvider,
    DayPlanningInput,
    PlannedDayProposal,
    PlanningContextEntry,
    PlanningKnownEvent,
    PlanningUpcomingEvent,
    PreparedDayResult,
    PreparedEvent,
    ProposedDailyEvent,
)
from app.application.push.changes import DailyPlanChanges
from app.application.upcoming import project_upcoming
from app.core.config import Settings
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel, UserContextEntryModel


class PlanningValidationError(ValueError):
    pass


class DayPlanningService:
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

    async def prepare(
        self,
        user_id: uuid.UUID,
        target_date: date,
        morning_brief_time: time | None = None,
    ) -> PreparedDayResult:
        planning_input = await self._load_input(user_id, target_date)
        proposal = await self._ai.plan_day(planning_input)
        proposed = self._normalize(proposal, planning_input.known_events)
        final_for_brief = [
            event.model_dump(mode="json")
            for event in sorted(
                [*planning_input.known_events, *[self._known(event) for event in proposed]],
                key=lambda event: DayPlanningService._time_sort_key(event.start_time),
            )
        ]
        brief = await self._ai.compose_morning_brief(planning_input, final_for_brief)
        brief_time = (
            morning_brief_time
            or proposal.morning_brief_time
            or self._settings.morning_brief_default_time
        )
        return await self._persist(user_id, target_date, proposed, brief.content, brief_time)

    async def _load_input(self, user_id: uuid.UUID, target_date: date) -> DayPlanningInput:
        async with self._session_factory() as session:
            context_result = await session.execute(
                select(UserContextEntryModel)
                .where(
                    UserContextEntryModel.user_id == user_id,
                    UserContextEntryModel.deleted_at.is_(None),
                    or_(
                        UserContextEntryModel.starts_on.is_(None),
                        UserContextEntryModel.starts_on <= target_date,
                    ),
                    or_(
                        UserContextEntryModel.ends_on.is_(None),
                        UserContextEntryModel.ends_on >= target_date,
                    ),
                )
                .order_by(UserContextEntryModel.created_at.desc())
                .limit(self._settings.butler_user_context_limit)
            )
            event_result = await session.execute(
                select(DailyEventModel)
                .where(
                    DailyEventModel.user_id == user_id,
                    DailyEventModel.event_date == target_date,
                    DailyEventModel.deleted_at.is_(None),
                    DailyEventModel.origin != "planner",
                )
                .order_by(
                    DailyEventModel.start_time.is_(None),
                    DailyEventModel.start_time,
                    DailyEventModel.sort_order,
                )
            )
            contexts = context_result.scalars().all()
            known = event_result.scalars().all()

        return DayPlanningInput(
            target_date=target_date,
            weekday=target_date.strftime("%A"),
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
            upcoming_events=[
                PlanningUpcomingEvent(
                    source_context_id=item.source_context_id,
                    occurrence_date=item.occurrence_date,
                    title=item.title,
                    description=item.description,
                    starts_on=item.starts_on,
                    ends_on=item.ends_on,
                    start_time=item.start_time,
                    end_time=item.end_time,
                )
                for item in project_upcoming(list(contexts), target_date)
                if item.starts_on <= target_date <= item.ends_on
            ],
            known_events=[self._known_model(row) for row in known],
        )

    @staticmethod
    def _time_sort_key(value: time | None) -> tuple[bool, int]:
        """Return a tuple suitable for sorting times, with None values considered last."""
        if value is None:
            return True, 24 * 60 * 60

        return False, value.hour * 3600 + value.minute * 60 + value.second

    @staticmethod
    def _normalize(
        proposal: PlannedDayProposal,
        known_events: Sequence[PlanningKnownEvent],
    ) -> list[ProposedDailyEvent]:
        events: list[ProposedDailyEvent] = []
        for event in proposal.events:
            if event.start_time is None:
                if event.end_time is not None:
                    raise PlanningValidationError("An unscheduled event cannot have an end time")
                event = event.model_copy(update={"scheduled_precision": "unscheduled"})
            elif event.end_time is not None and event.end_time <= event.start_time:
                raise PlanningValidationError("Event end time must be after its start time")
            elif event.scheduled_precision == "unscheduled":
                event = event.model_copy(update={"scheduled_precision": "exact"})
            if DayPlanningService._overlaps_known(event, known_events):
                continue  # Protected commitments win over speculative generated events.
            events.append(event.model_copy(update={"speak_aloud": False}))
        return sorted(
            events,
            key=lambda event: (
                *DayPlanningService._time_sort_key(event.start_time),
                event.title.casefold(),
            ),
        )

    @staticmethod
    def _overlaps_known(
        proposed: ProposedDailyEvent,
        known_events: Sequence[PlanningKnownEvent],
    ) -> bool:
        proposed_bounds = DayPlanningService._bounds(
            proposed.start_time, proposed.end_time, proposed.duration_minutes
        )
        if proposed_bounds is None:
            return False
        for known in known_events:
            known_bounds = DayPlanningService._bounds(
                known.start_time, known.end_time, known.duration_minutes
            )
            if (
                known_bounds
                and proposed_bounds[0] < known_bounds[1]
                and known_bounds[0] < proposed_bounds[1]
            ):
                return True
        return False

    @staticmethod
    def _bounds(
        start: time | None, end: time | None, duration_minutes: int | None
    ) -> tuple[int, int] | None:
        if start is None:
            return None
        start_minutes = start.hour * 60 + start.minute
        if end is not None:
            return start_minutes, end.hour * 60 + end.minute
        return start_minutes, min(start_minutes + (duration_minutes or 30), 1440)

    async def _persist(
        self,
        user_id: uuid.UUID,
        target_date: date,
        proposed: Sequence[ProposedDailyEvent],
        brief_content: str,
        brief_time: time,
    ) -> PreparedDayResult:
        async with self._changes.transaction(user_id) as session:
            await session.execute(
                insert(DailyPlanModel)
                .values(id=uuid.uuid4(), user_id=user_id, plan_date=target_date, status="planned")
                .on_conflict_do_nothing(index_elements=["user_id", "plan_date"])
            )
            plan = (
                await session.execute(
                    select(DailyPlanModel)
                    .where(
                        DailyPlanModel.user_id == user_id,
                        DailyPlanModel.plan_date == target_date,
                    )
                    .with_for_update()
                )
            ).scalar_one()
            plan.deleted_at = None
            plan.status = "planned"
            generated = list(
                (
                    await session.execute(
                        select(DailyEventModel).where(
                            DailyEventModel.user_id == user_id,
                            DailyEventModel.event_date == target_date,
                            DailyEventModel.origin == "planner",
                            DailyEventModel.deleted_at.is_(None),
                        )
                    )
                ).scalars()
            )
            by_key = {event.planner_key: event for event in generated if event.planner_key}
            desired = [(f"planned_{index:03d}", event) for index, event in enumerate(proposed)]
            desired_keys = {key for key, _ in desired} | {"morning_brief"}
            for stale in generated:
                if stale.planner_key not in desired_keys:
                    await session.delete(stale)

            for sort_order, (key, event) in enumerate(desired, start=1):
                row = by_key.get(key)
                if row is None:
                    row = DailyEventModel(
                        id=uuid.uuid4(),
                        user_id=user_id,
                        daily_plan_id=plan.id,
                        title=event.title,
                        event_type=event.event_type,
                        status="planned",
                        event_date=target_date,
                        origin="planner",
                        planner_key=key,
                    )
                self._apply(row, plan.id, event, sort_order, row in generated)
                session.add(row)

            brief = by_key.get("morning_brief")
            brief_exists = brief is not None
            if brief is None:
                brief = DailyEventModel(
                    id=uuid.uuid4(),
                    user_id=user_id,
                    daily_plan_id=plan.id,
                    title="Morning Brief",
                    event_type="morning_brief",
                    status="planned",
                    event_date=target_date,
                    origin="planner",
                    planner_key="morning_brief",
                )
            brief_changed = brief_exists and (
                brief.start_time != brief_time
                or brief.content != brief_content
                or brief.status != "planned"
                or not brief.speak_aloud
            )
            brief.daily_plan_id = plan.id
            brief.title = "Morning Brief"
            brief.description = "Butler's prepared introduction to your day"
            brief.event_type = "morning_brief"
            brief.status = "planned"
            brief.start_time = brief_time
            brief.end_time = None
            brief.duration_minutes = None
            brief.scheduled_precision = "exact"
            brief.content = brief_content
            brief.reminder_minutes_before = None
            brief.speak_aloud = True
            brief.sort_order = 0
            brief.origin = "planner"
            brief.version = (
                (brief.version + 1 if brief_changed else brief.version) if brief_exists else 1
            )
            session.add(brief)
            await session.flush()
            final_rows = list(
                (
                    await session.execute(
                        select(DailyEventModel)
                        .where(
                            DailyEventModel.user_id == user_id,
                            DailyEventModel.daily_plan_id == plan.id,
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

            return PreparedDayResult(
                plan_id=plan.id,
                target_date=target_date,
                events=[
                    PreparedEvent(
                        id=row.id,
                        title=row.title,
                        event_type=row.event_type,
                        start_time=row.start_time,
                        origin=row.origin,
                    )
                    for row in final_rows
                ],
                morning_brief=brief_content,
            )

    @staticmethod
    def _apply(
        row: DailyEventModel,
        plan_id: uuid.UUID,
        event: ProposedDailyEvent,
        sort_order: int,
        existing: bool,
    ) -> None:
        desired = (
            plan_id,
            event.title,
            event.description,
            event.event_type,
            "planned",
            event.start_time,
            event.end_time,
            event.duration_minutes,
            event.scheduled_precision,
            event.content,
            event.reminder_minutes_before,
            False,
            sort_order,
            "planner",
        )
        current = (
            row.daily_plan_id,
            row.title,
            row.description,
            row.event_type,
            row.status,
            row.start_time,
            row.end_time,
            row.duration_minutes,
            row.scheduled_precision,
            row.content,
            row.reminder_minutes_before,
            row.speak_aloud,
            row.sort_order,
            row.origin,
        )
        row.daily_plan_id = plan_id
        row.title = event.title
        row.description = event.description
        row.event_type = event.event_type
        row.status = "planned"
        row.start_time = event.start_time
        row.end_time = event.end_time
        row.duration_minutes = event.duration_minutes
        row.scheduled_precision = event.scheduled_precision
        row.content = event.content
        row.reminder_minutes_before = event.reminder_minutes_before
        row.speak_aloud = False
        row.sort_order = sort_order
        row.origin = "planner"
        row.version = (row.version + 1 if current != desired else row.version) if existing else 1

    @staticmethod
    def _known(event: ProposedDailyEvent) -> PlanningKnownEvent:
        return PlanningKnownEvent(**event.model_dump(), origin="planner")

    @staticmethod
    def _known_model(row: DailyEventModel) -> PlanningKnownEvent:
        return PlanningKnownEvent(
            id=row.id,
            title=row.title,
            description=row.description,
            event_type=row.event_type,
            start_time=row.start_time,
            end_time=row.end_time,
            duration_minutes=row.duration_minutes,
            content=row.content,
            scheduled_precision=row.scheduled_precision,
            reminder_minutes_before=row.reminder_minutes_before,
            speak_aloud=row.speak_aloud,
            origin=row.origin,
        )
