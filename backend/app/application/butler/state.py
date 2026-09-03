from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import NotRequired, TypedDict

from app.application.butler.contracts import (
    ButlerContext,
    ButlerDecision,
    ButlerError,
    ButlerResult,
    InteractionMode,
)


class ButlerState(TypedDict):
    user_id: uuid.UUID
    interaction_mode: InteractionMode
    message: str
    now: datetime
    timezone: str
    today: date
    context: NotRequired[ButlerContext]
    decision: NotRequired[ButlerDecision]
    result: NotRequired[ButlerResult]


class ButlerStateUpdate(TypedDict, total=False):
    context: ButlerContext
    decision: ButlerDecision
    result: ButlerResult


def context_from(state: ButlerState) -> ButlerContext:
    context = state.get("context")
    if context is None:
        raise ButlerError("Butler graph state is missing context")
    return context


def decision_from(state: ButlerState) -> ButlerDecision:
    decision = state.get("decision")
    if decision is None:
        raise ButlerError("Butler graph state is missing decision")
    return decision


def result_from(state: ButlerState) -> ButlerResult:
    result = state.get("result")
    if result is None:
        raise ButlerError("Butler graph state is missing result")
    return result
