from __future__ import annotations

import uuid
from datetime import date, datetime, time
from typing import Annotated, Literal

from pydantic import BaseModel, Field, model_validator

SyncAction = Literal["create", "edit", "complete", "skip", "delay", "cancel", "delete"]
SyncResultStatus = Literal["applied", "duplicate", "conflict"]
EventStatus = Literal["planned", "completed", "skipped", "cancelled"]


class DailyEventMutation(BaseModel):
    id: uuid.UUID
    event_date: date
    title: Annotated[str, Field(min_length=1, max_length=200)]
    description: Annotated[str | None, Field(max_length=2000)] = None
    event_type: Annotated[str, Field(min_length=1, max_length=60)]
    status: EventStatus = "planned"
    start_time: time | None = None
    end_time: time | None = None
    duration_minutes: Annotated[int | None, Field(ge=1, le=1440)] = None
    scheduled_precision: Annotated[str | None, Field(max_length=40)] = None
    content: Annotated[str | None, Field(max_length=5000)] = None
    reminder_minutes_before: Annotated[int | None, Field(ge=0, le=10080)] = None
    speak_aloud: bool = False
    sort_order: int = 0

    @model_validator(mode="after")
    def valid_times(self) -> DailyEventMutation:
        if self.end_time is not None and self.start_time is None:
            raise ValueError("end_time requires start_time")
        if (
            self.start_time is not None
            and self.end_time is not None
            and self.end_time <= self.start_time
        ):
            raise ValueError("end_time must be after start_time")
        return self


class EventSyncOperation(BaseModel):
    operation_id: uuid.UUID
    action: SyncAction
    event_id: uuid.UUID
    base_version: Annotated[int, Field(ge=0)]
    event: DailyEventMutation | None = None

    @model_validator(mode="after")
    def payload_matches_action(self) -> EventSyncOperation:
        if self.action != "delete" and self.event is None:
            raise ValueError("event is required unless action is delete")
        if self.event is not None and self.event.id != self.event_id:
            raise ValueError("event.id must match event_id")
        return self


class DailyEventState(DailyEventMutation):
    daily_plan_id: uuid.UUID
    version: int
    origin: str
    updated_at: datetime
    deleted_at: datetime | None = None


class EventSyncResult(BaseModel):
    operation_id: uuid.UUID
    status: SyncResultStatus
    event: DailyEventState | None


class SyncBatchResult(BaseModel):
    results: list[EventSyncResult]
