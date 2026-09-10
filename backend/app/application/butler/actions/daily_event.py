from __future__ import annotations

import uuid
from datetime import date, time

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.application.butler.contracts import ButlerResult, ChangedEntity
from app.application.butler.state import ButlerState, ButlerStateUpdate, decision_from
from app.application.push.changes import DailyPlanChanges
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel


class DailyEventActions:
    def __init__(self, changes: DailyPlanChanges) -> None:
        self._changes = changes

    async def create(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        if not decision.title:
            return self._follow_up("What should I call this event?")

        event_date = decision.event_date or state["today"]
        async with self._changes.transaction(state["user_id"]) as session:
            plan = await self._get_or_create_plan(session, state["user_id"], event_date)
            event = DailyEventModel(
                id=uuid.uuid4(),
                user_id=state["user_id"],
                daily_plan_id=plan.id,
                title=decision.title,
                description=decision.description,
                event_type=decision.event_type or "reminder",
                status="planned",
                event_date=event_date,
                start_time=decision.start_time,
                end_time=decision.end_time,
                duration_minutes=decision.duration_minutes,
                scheduled_precision="exact" if decision.start_time else None,
                reminder_minutes_before=decision.reminder_minutes_before,
                speak_aloud=False,
                sort_order=0,
                version=1,
                origin="user",
            )
            session.add(event)
            changed = ChangedEntity(type="daily_event", id=event.id, plan_dates=[event.event_date])

        when = self._format_time(event_date, decision.start_time)
        return {
            "result": ButlerResult(
                response=f"Done. I added {decision.title} {when}.",
                changed_entities=[changed],
            )
        }

    async def update(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        async with self._changes.transaction(state["user_id"]) as session:
            event = await self._resolve_event(session, state)
            if event is None:
                return self._follow_up("Which event should I update?")

            previous_date = event.event_date
            changed_fields = False
            if decision.title:
                event.title = decision.title
                changed_fields = True
            if decision.description is not None:
                event.description = decision.description
                changed_fields = True
            if decision.event_type:
                event.event_type = decision.event_type
                changed_fields = True
            if decision.start_time is not None:
                event.start_time = decision.start_time
                event.scheduled_precision = "exact"
                changed_fields = True
            if decision.end_time is not None:
                event.end_time = decision.end_time
                changed_fields = True
            if decision.duration_minutes is not None:
                event.duration_minutes = decision.duration_minutes
                changed_fields = True
            if decision.reminder_minutes_before is not None:
                event.reminder_minutes_before = decision.reminder_minutes_before
                changed_fields = True
            if decision.event_date is not None and decision.event_date != event.event_date:
                plan = await self._get_or_create_plan(
                    session, state["user_id"], decision.event_date
                )
                event.event_date = decision.event_date
                event.daily_plan_id = plan.id
                changed_fields = True
            if not changed_fields:
                return self._follow_up("What should I change about it?")

            event.origin = "user"  # An explicit user change becomes protected planning input.
            event.planner_key = None
            event.version += 1
            changed = ChangedEntity(type="daily_event", id=event.id, plan_dates=[event.event_date])

        return {
            "result": ButlerResult(
                response=f"Done. I updated {event.title}.",
                changed_entities=[
                    changed.model_copy(
                        update={"plan_dates": sorted({previous_date, event.event_date})}
                    )
                ],
            )
        }

    async def skip(self, state: ButlerState) -> ButlerStateUpdate:
        async with self._changes.transaction(state["user_id"]) as session:
            event = await self._resolve_event(session, state)
            if event is None:
                return self._follow_up("Which event should I skip?")
            if event.status == "skipped":
                return {"result": ButlerResult(response=f"{event.title} is already skipped.")}
            event.status = "skipped"
            event.origin = "user"
            event.planner_key = None
            event.version += 1
            changed = ChangedEntity(type="daily_event", id=event.id, plan_dates=[event.event_date])

        return {
            "result": ButlerResult(
                response=f"Done. I skipped {event.title}.", changed_entities=[changed]
            )
        }

    async def _get_or_create_plan(
        self, session: AsyncSession, user_id: uuid.UUID, plan_date: date
    ) -> DailyPlanModel:
        result = await session.execute(
            select(DailyPlanModel).where(
                DailyPlanModel.user_id == user_id,
                DailyPlanModel.plan_date == plan_date,
                DailyPlanModel.deleted_at.is_(None),
            )
        )
        plan = result.scalar_one_or_none()
        if plan is not None:
            return plan

        plan = DailyPlanModel(
            id=uuid.uuid4(), user_id=user_id, plan_date=plan_date, status="planned"
        )
        session.add(plan)
        await session.flush()
        return plan

    async def _resolve_event(
        self, session: AsyncSession, state: ButlerState
    ) -> DailyEventModel | None:
        decision = decision_from(state)
        if decision.target_event_id is not None:
            result = await session.execute(
                select(DailyEventModel)
                .where(
                    DailyEventModel.id == decision.target_event_id,
                    DailyEventModel.user_id == state["user_id"],
                    DailyEventModel.deleted_at.is_(None),
                )
                .with_for_update()
            )
            return result.scalar_one_or_none()

        if not decision.target_event_title:
            return None
        event_date = decision.event_date or state["today"]
        result = await session.execute(
            select(DailyEventModel)
            .where(
                DailyEventModel.user_id == state["user_id"],
                DailyEventModel.event_date == event_date,
                DailyEventModel.deleted_at.is_(None),
                DailyEventModel.title.ilike(f"%{decision.target_event_title}%"),
            )
            .order_by(DailyEventModel.start_time.is_(None), DailyEventModel.start_time)
            .limit(2)
            .with_for_update()
        )
        matches = list(result.scalars())
        return matches[0] if len(matches) == 1 else None

    @staticmethod
    def _follow_up(question: str) -> ButlerStateUpdate:
        return {"result": ButlerResult(response=question, requires_follow_up=True)}

    @staticmethod
    def _format_time(event_date: date, start_time: time | None) -> str:
        if start_time is None:
            return f"on {event_date.isoformat()}"
        return f"on {event_date.isoformat()} at {start_time.strftime('%H:%M')}"
