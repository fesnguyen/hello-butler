from __future__ import annotations

import uuid
from datetime import date, time
from typing import Annotated, Literal, Protocol

from pydantic import BaseModel, Field, field_validator

EventType = Literal[
    "wake_up",
    "routine",
    "work",
    "exercise",
    "meeting",
    "appointment",
    "reminder",
    "meal",
    "commute",
    "personal",
    "custom",
]
ScheduledPrecision = Literal["exact", "approximate", "unscheduled"]


class PlanningContextEntry(BaseModel):
    context_type: str
    content: str
    starts_on: date | None = None
    ends_on: date | None = None


class PlanningKnownEvent(BaseModel):
    id: uuid.UUID | None = None
    title: str
    description: str | None = None
    event_type: str
    start_time: time | None = None
    end_time: time | None = None
    duration_minutes: int | None = None
    content: str | None = None
    scheduled_precision: str | None = None
    reminder_minutes_before: int | None = None
    speak_aloud: bool = False
    origin: str = "user"


class DayPlanningInput(BaseModel):
    target_date: date
    weekday: str
    timezone: str
    user_context: list[PlanningContextEntry]
    known_events: list[PlanningKnownEvent]


class ProposedDailyEvent(BaseModel):
    title: Annotated[str, Field(min_length=1, max_length=200)]
    description: Annotated[str | None, Field(max_length=2000)] = None
    event_type: EventType
    start_time: time | None = None
    end_time: time | None = None
    duration_minutes: Annotated[int | None, Field(ge=1, le=1440)] = None
    scheduled_precision: ScheduledPrecision
    content: Annotated[str | None, Field(max_length=5000)] = None
    reminder_minutes_before: Annotated[int | None, Field(ge=0, le=10080)] = None
    speak_aloud: bool = False

    @field_validator("title")
    @classmethod
    def strip_title(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("title must not be blank")
        return stripped

    @field_validator("description", "content")
    @classmethod
    def strip_optional_text(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None


class PlannedDayProposal(BaseModel):
    events: Annotated[list[ProposedDailyEvent], Field(max_length=30)]
    morning_brief_time: time | None = None


class MorningBriefDraft(BaseModel):
    content: Annotated[str, Field(min_length=1, max_length=4000)]

    @field_validator("content")
    @classmethod
    def strip_content(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Morning Brief content must not be blank")
        return value


class PreparedEvent(BaseModel):
    id: uuid.UUID
    title: str
    event_type: str
    start_time: time | None
    origin: str


class PreparedDayResult(BaseModel):
    plan_id: uuid.UUID
    target_date: date
    events: list[PreparedEvent]
    morning_brief: str


class DayPlanningAIProvider(Protocol):
    async def plan_day(self, planning_input: DayPlanningInput) -> PlannedDayProposal: ...

    async def compose_morning_brief(
        self, planning_input: DayPlanningInput, final_events: list[dict[str, object]]
    ) -> MorningBriefDraft: ...
