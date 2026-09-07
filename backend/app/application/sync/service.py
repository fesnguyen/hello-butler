from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import cast

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.sync.contracts import (
    DailyEventMutation,
    DailyEventState,
    EventStatus,
    EventSyncOperation,
    EventSyncResult,
    SyncBatchResult,
)
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel, SyncOperationModel


class DailyEventSyncService:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def apply(
        self, user_id: uuid.UUID, operations: list[EventSyncOperation]
    ) -> SyncBatchResult:
        results: list[EventSyncResult] = []
        for operation in operations:
            async with self._session_factory() as session, session.begin():
                results.append(await self._apply_one(session, user_id, operation))
        return SyncBatchResult(results=results)

    async def _apply_one(
        self, session: AsyncSession, user_id: uuid.UUID, operation: EventSyncOperation
    ) -> EventSyncResult:
        receipt = (
            await session.execute(
                select(SyncOperationModel).where(
                    SyncOperationModel.id == operation.operation_id,
                    SyncOperationModel.user_id == user_id,
                )
            )
        ).scalar_one_or_none()
        if receipt is not None:
            event = await self._event(session, user_id, receipt.event_id, lock=False)
            return EventSyncResult(
                operation_id=operation.operation_id,
                status="duplicate",
                event=self._state(event) if event is not None else None,
            )

        event = await self._event(session, user_id, operation.event_id, lock=True)
        if operation.action == "create":
            if event is not None or operation.base_version != 0:
                return self._conflict(operation, event)
            mutation = operation.event
            if mutation is None:  # Guarded by the validated contract.
                raise ValueError("create operation requires an event")
            plan = await self._plan(session, user_id, mutation.event_date)
            event = DailyEventModel(
                id=operation.event_id,
                user_id=user_id,
                daily_plan_id=plan.id,
                event_date=mutation.event_date,
                title=mutation.title,
                event_type=mutation.event_type,
                version=1,
                origin="user",
            )
            self._apply_mutation(event, mutation)
            session.add(event)
        else:
            if event is None or event.version != operation.base_version:
                return self._conflict(operation, event)
            if operation.action == "delete":
                event.deleted_at = datetime.now(UTC)
                event.origin = "user"
                event.planner_key = None
                event.version += 1
            else:
                mutation = operation.event
                if mutation is None:  # Guarded by the validated contract.
                    raise ValueError("mutation operation requires an event")
                if mutation.event_date != event.event_date:
                    event.daily_plan_id = (
                        await self._plan(session, user_id, mutation.event_date)
                    ).id
                self._apply_mutation(event, mutation)
                if operation.action == "complete":
                    event.status = "completed"
                elif operation.action == "skip":
                    event.status = "skipped"
                elif operation.action == "cancel":
                    event.status = "cancelled"
                event.deleted_at = None
                event.origin = "user"
                event.planner_key = None
                event.version += 1

        await session.flush()
        session.add(
            SyncOperationModel(
                id=operation.operation_id,
                user_id=user_id,
                event_id=event.id,
                operation_type=operation.action,
                result_version=event.version,
                deleted_at=event.deleted_at,
            )
        )
        await session.flush()
        return EventSyncResult(
            operation_id=operation.operation_id, status="applied", event=self._state(event)
        )

    @staticmethod
    async def _event(
        session: AsyncSession, user_id: uuid.UUID, event_id: uuid.UUID, *, lock: bool
    ) -> DailyEventModel | None:
        statement = select(DailyEventModel).where(
            DailyEventModel.id == event_id, DailyEventModel.user_id == user_id
        )
        if lock:
            statement = statement.with_for_update()
        return (await session.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def _plan(session: AsyncSession, user_id: uuid.UUID, plan_date: object) -> DailyPlanModel:
        await session.execute(
            insert(DailyPlanModel)
            .values(id=uuid.uuid4(), user_id=user_id, plan_date=plan_date, status="planned")
            .on_conflict_do_nothing(index_elements=["user_id", "plan_date"])
        )
        plan = (
            await session.execute(
                select(DailyPlanModel).where(
                    DailyPlanModel.user_id == user_id, DailyPlanModel.plan_date == plan_date
                )
            )
        ).scalar_one()
        plan.deleted_at = None
        return plan

    @staticmethod
    def _apply_mutation(row: DailyEventModel, value: DailyEventMutation) -> None:
        row.event_date = value.event_date
        row.title = value.title.strip()
        row.description = value.description
        row.event_type = value.event_type
        row.status = value.status
        row.start_time = value.start_time
        row.end_time = value.end_time
        row.duration_minutes = value.duration_minutes
        row.scheduled_precision = value.scheduled_precision
        row.content = value.content
        row.reminder_minutes_before = value.reminder_minutes_before
        row.speak_aloud = value.speak_aloud
        row.sort_order = value.sort_order

    @staticmethod
    def _conflict(operation: EventSyncOperation, event: DailyEventModel | None) -> EventSyncResult:
        return EventSyncResult(
            operation_id=operation.operation_id,
            status="conflict",
            event=DailyEventSyncService._state(event) if event is not None else None,
        )

    @staticmethod
    def _state(row: DailyEventModel) -> DailyEventState:
        return DailyEventState(
            id=row.id,
            daily_plan_id=row.daily_plan_id,
            event_date=row.event_date,
            title=row.title,
            description=row.description,
            event_type=row.event_type,
            status=cast(EventStatus, row.status),
            start_time=row.start_time,
            end_time=row.end_time,
            duration_minutes=row.duration_minutes,
            scheduled_precision=row.scheduled_precision,
            content=row.content,
            reminder_minutes_before=row.reminder_minutes_before,
            speak_aloud=row.speak_aloud,
            sort_order=row.sort_order,
            version=row.version,
            origin=row.origin,
            updated_at=row.updated_at,
            deleted_at=row.deleted_at,
        )
