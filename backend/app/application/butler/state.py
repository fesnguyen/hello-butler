from __future__ import annotations

import uuid
from datetime import date, datetime
from pathlib import Path
from typing import NotRequired, TypedDict

from app.application.butler.contracts import (
    ButlerAIInteraction,
    ButlerContext,
    ButlerDecision,
    ButlerError,
    ButlerResult,
    InteractionMode,
)


class ButlerState(TypedDict):
    request_id: NotRequired[uuid.UUID]
    user_id: uuid.UUID
    interaction_mode: InteractionMode
    message: str
    audio_path: Path | None
    audio_mime_type: str | None
    now: datetime
    timezone: str
    today: date
    context: NotRequired[ButlerContext]
    decision: NotRequired[ButlerDecision]
    result: NotRequired[ButlerResult]
    interaction: NotRequired[ButlerAIInteraction]


class ButlerStateUpdate(TypedDict, total=False):
    message: str
    context: ButlerContext
    decision: ButlerDecision
    result: ButlerResult
    interaction: ButlerAIInteraction


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


def interaction_from(state: ButlerState) -> ButlerAIInteraction:
    interaction = state.get("interaction")
    if interaction is None:
        raise ButlerError("Butler graph state is missing multimodal interaction")
    return interaction


def canonical_response_from(state: ButlerState) -> str:
    return interaction_from(state).proposal.response_text
