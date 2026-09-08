import uuid
from datetime import date, datetime
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.sync import DailyEventSyncService, EventSyncOperation, SyncBatchResult
from app.core.config import Settings, get_settings
from app.core.database import get_session
from app.core.lifecycle import daily_plan_changes
from app.infrastructure.db.models import DailyEventModel, DailyPlanModel

router = APIRouter(prefix="/api/sync", tags=["sync"])
SessionDep = Annotated[AsyncSession, Depends(get_session)]
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]
SettingsDep = Annotated[Settings, Depends(get_settings)]


class SyncEvent(BaseModel):
    id: uuid.UUID
    daily_plan_id: uuid.UUID
    event_date: date
    title: str
    description: str | None
    event_type: str
    status: str
    start_time: str | None
    end_time: str | None
    duration_minutes: int | None
    scheduled_precision: str | None
    content: str | None
    reminder_minutes_before: int | None
    speak_aloud: bool
    sort_order: int
    version: int
    origin: str
    updated_at: datetime


class SyncPlan(BaseModel):
    id: uuid.UUID
    plan_date: date
    status: str
    updated_at: datetime


class DailyPlanSnapshot(BaseModel):
    plan: SyncPlan | None
    events: list[SyncEvent]


class SyncBatchRequest(BaseModel):
    operations: list[EventSyncOperation] = Field(min_length=1, max_length=100)


@router.post("/events", response_model=SyncBatchResult)
async def sync_events(
    request: SyncBatchRequest,
    user: AuthenticatedUserDep,
    settings: SettingsDep,
) -> SyncBatchResult:
    return await DailyEventSyncService(daily_plan_changes(settings)).apply(
        user.id, request.operations
    )


@router.get("/daily-plan/{plan_date}", response_model=DailyPlanSnapshot)
async def get_daily_plan(
    plan_date: date,
    user: AuthenticatedUserDep,
    session: SessionDep,
) -> DailyPlanSnapshot:
    plan = (
        await session.execute(
            select(DailyPlanModel).where(
                DailyPlanModel.user_id == user.id,
                DailyPlanModel.plan_date == plan_date,
                DailyPlanModel.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if plan is None:
        return DailyPlanSnapshot(plan=None, events=[])
    rows = (
        (
            await session.execute(
                select(DailyEventModel)
                .where(
                    DailyEventModel.user_id == user.id,
                    DailyEventModel.daily_plan_id == plan.id,
                    DailyEventModel.deleted_at.is_(None),
                )
                .order_by(
                    DailyEventModel.start_time.is_(None),
                    DailyEventModel.start_time,
                    DailyEventModel.sort_order,
                )
            )
        )
        .scalars()
        .all()
    )
    return DailyPlanSnapshot(
        plan=SyncPlan(
            id=plan.id,
            plan_date=plan.plan_date,
            status=plan.status,
            updated_at=plan.updated_at,
        ),
        events=[
            SyncEvent(
                id=row.id,
                daily_plan_id=row.daily_plan_id,
                event_date=row.event_date,
                title=row.title,
                description=row.description,
                event_type=row.event_type,
                status=row.status,
                start_time=(
                    row.start_time.isoformat(timespec="minutes") if row.start_time else None
                ),
                end_time=row.end_time.isoformat(timespec="minutes") if row.end_time else None,
                duration_minutes=row.duration_minutes,
                scheduled_precision=row.scheduled_precision,
                content=row.content,
                reminder_minutes_before=row.reminder_minutes_before,
                speak_aloud=row.speak_aloud,
                sort_order=row.sort_order,
                version=row.version,
                origin=row.origin,
                updated_at=row.updated_at,
            )
            for row in rows
        ],
    )
