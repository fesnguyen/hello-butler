from __future__ import annotations

import uuid
from datetime import date, time
from typing import Annotated, Literal, Protocol

from pydantic import BaseModel, Field

InteractionMode = Literal["order", "talk"]
Intent = Literal["command", "query", "clarify"]
RequestedAction = Literal[
    "create_daily_event",
    "update_daily_event",
    "skip_daily_event",
    "remember_user_context",
    "answer_today_events",
    "none",
]


class ContextMessage(BaseModel):
    role: str
    content: str


class ContextEntry(BaseModel):
    id: uuid.UUID
    context_type: str
    content: str
    starts_on: date | None
    ends_on: date | None


class ContextPlan(BaseModel):
    id: uuid.UUID
    plan_date: date
    status: str


class ContextEvent(BaseModel):
    id: uuid.UUID
    title: str
    description: str | None
    event_type: str
    status: str
    event_date: date
    start_time: time | None
    end_time: time | None
    duration_minutes: int | None


class ButlerContext(BaseModel):
    conversation_history: list[ContextMessage]
    user_context: list[ContextEntry]
    daily_plan: ContextPlan | None
    relevant_events: list[ContextEvent]


class ButlerDecision(BaseModel):
    intent: Intent
    requested_action: RequestedAction = "none"
    target_event_id: uuid.UUID | None = None
    target_event_title: str | None = None
    title: str | None = None
    description: str | None = None
    event_date: date | None = None
    start_time: time | None = None
    end_time: time | None = None
    duration_minutes: Annotated[int | None, Field(ge=1, le=1440)] = None
    event_type: str | None = None
    reminder_minutes_before: Annotated[int | None, Field(ge=0, le=10080)] = None
    context_type: str | None = None
    context_content: str | None = None
    missing_information: list[str] = Field(default_factory=list)
    clarification_question: str | None = None
    answer: str | None = None


class ChangedEntity(BaseModel):
    type: str
    id: uuid.UUID


class ButlerResult(BaseModel):
    response: str
    changed_entities: list[ChangedEntity] = Field(default_factory=list[ChangedEntity])
    requires_follow_up: bool = False


class ButlerAIProvider(Protocol):
    async def understand(
        self,
        *,
        message: str,
        interaction_mode: str,
        now: str,
        timezone: str,
        context: ButlerContext,
    ) -> ButlerDecision: ...


class ButlerError(RuntimeError):
    pass


class ButlerAIUnavailableError(ButlerError):
    pass
