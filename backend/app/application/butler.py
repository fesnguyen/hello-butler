# pyright: reportMissingTypeStubs=false, reportUnknownMemberType=false
from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable, Sequence
from datetime import date, datetime, time
from typing import Annotated, Literal, NotRequired, Protocol, TypedDict, cast
from zoneinfo import ZoneInfo

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import BaseModel, Field
from sqlalchemy import Select, or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.config import Settings
from app.infrastructure.db.models import (
    ConversationMessageModel,
    DailyEventModel,
    DailyPlanModel,
    UserContextEntryModel,
)

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


class ButlerService:
    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        ai_provider: ButlerAIProvider,
    ) -> None:
        self._settings = settings
        self._session_factory = session_factory
        self._ai_provider = ai_provider
        self._graph: CompiledStateGraph[ButlerState, None, ButlerState, ButlerState] = (
            self._build_graph()
        )

    async def handle(
        self, *, user_id: uuid.UUID, interaction_mode: InteractionMode, message: str
    ) -> ButlerResult:
        normalized = message.strip()
        if not normalized:
            return ButlerResult(
                response="What would you like me to help with?", requires_follow_up=True
            )

        timezone = self._settings.butler_default_timezone
        now = datetime.now(ZoneInfo(timezone))
        state: ButlerState = {
            "user_id": user_id,
            "interaction_mode": interaction_mode,
            "message": normalized,
            "now": now,
            "timezone": timezone,
            "today": now.date(),
        }
        result = cast(ButlerState, await self._graph.ainvoke(state))
        butler_result = result.get("result")
        if butler_result is None:
            raise ButlerError("Butler graph completed without a result")
        return butler_result

    def _build_graph(self) -> CompiledStateGraph[ButlerState, None, ButlerState, ButlerState]:
        graph = StateGraph(ButlerState)
        graph.add_node("load_context", self._load_context_node)
        graph.add_node("understand_request", self._understand_request_node)
        graph.add_node("apply_action", self._apply_action_node)
        graph.add_node("build_query_result", self._build_query_result_node)
        graph.add_node("build_clarification", self._build_clarification_node)
        graph.add_node("save_history", self._save_history_node)

        graph.add_edge(START, "load_context")
        graph.add_edge("load_context", "understand_request")
        graph.add_conditional_edges(
            "understand_request",
            self._route,
            {
                "command": "apply_action",
                "query": "build_query_result",
                "clarify": "build_clarification",
            },
        )
        graph.add_edge("apply_action", "save_history")
        graph.add_edge("build_query_result", "save_history")
        graph.add_edge("build_clarification", "save_history")
        graph.add_edge("save_history", END)
        return graph.compile()

    async def _load_context_node(self, state: ButlerState) -> ButlerStateUpdate:
        context = await self._load_context(state["user_id"], state["today"])
        return {"context": context}

    async def _understand_request_node(self, state: ButlerState) -> ButlerStateUpdate:
        decision = await self._ai_provider.understand(
            message=state["message"],
            interaction_mode=state["interaction_mode"],
            now=state["now"].isoformat(),
            timezone=state["timezone"],
            context=self._context(state),
        )
        return {"decision": decision}

    def _route(self, state: ButlerState) -> Intent:
        decision = self._decision(state)
        if decision.intent == "command" and decision.missing_information:
            return "clarify"
        return decision.intent

    async def _apply_action_node(self, state: ButlerState) -> ButlerStateUpdate:
        decision = self._decision(state)
        handlers: dict[RequestedAction, Callable[[ButlerState], Awaitable[ButlerStateUpdate]]] = {
            "create_daily_event": self._create_daily_event,
            "update_daily_event": self._update_daily_event,
            "skip_daily_event": self._skip_daily_event,
            "remember_user_context": self._remember_user_context,
            "answer_today_events": self._build_query_result_node,
            "none": self._build_clarification_node,
        }
        handler = handlers.get(decision.requested_action, self._build_clarification_node)
        return await handler(state)

    async def _build_query_result_node(self, state: ButlerState) -> ButlerStateUpdate:
        answer = self._decision(state).answer or self._summarize_events(
            self._context(state).relevant_events
        )
        return {"result": ButlerResult(response=answer)}

    async def _build_clarification_node(self, state: ButlerState) -> ButlerStateUpdate:
        decision = self._decision(state)
        question = decision.clarification_question or "What detail should I use?"
        return {"result": ButlerResult(response=question, requires_follow_up=True)}

    async def _save_history_node(self, state: ButlerState) -> ButlerStateUpdate:
        result = self._result(state)
        decision = state.get("decision")
        async with self._session_factory() as session, session.begin():
            session.add_all(
                [
                    ConversationMessageModel(
                        user_id=state["user_id"],
                        role="user",
                        content=state["message"],
                        message_metadata={
                            "interaction_mode": state["interaction_mode"],
                            "intent": decision.intent if decision else None,
                        },
                    ),
                    ConversationMessageModel(
                        user_id=state["user_id"],
                        role="butler",
                        content=result.response,
                        message_metadata={
                            "requires_follow_up": result.requires_follow_up,
                            "changed_entities": [
                                entity.model_dump(mode="json") for entity in result.changed_entities
                            ],
                        },
                    ),
                ]
            )
        return {}

    async def _load_context(self, user_id: uuid.UUID, today: date) -> ButlerContext:
        async with self._session_factory() as session:
            history = await self._latest_messages(session, user_id)
            user_context = await self._active_context(session, user_id, today)
            daily_plan = await self._daily_plan(session, user_id, today)
            events = await self._events_for_day(session, user_id, today)

        return ButlerContext(
            conversation_history=[
                ContextMessage(role=row.role, content=row.content) for row in reversed(history)
            ],
            user_context=[
                ContextEntry(
                    id=row.id,
                    context_type=row.context_type,
                    content=row.content,
                    starts_on=row.starts_on,
                    ends_on=row.ends_on,
                )
                for row in user_context
            ],
            daily_plan=(
                ContextPlan(
                    id=daily_plan.id, plan_date=daily_plan.plan_date, status=daily_plan.status
                )
                if daily_plan
                else None
            ),
            relevant_events=[self._context_event(row) for row in events],
        )

    async def _latest_messages(
        self, session: AsyncSession, user_id: uuid.UUID
    ) -> Sequence[ConversationMessageModel]:
        result = await session.execute(
            select(ConversationMessageModel)
            .where(ConversationMessageModel.user_id == user_id)
            .order_by(ConversationMessageModel.created_at.desc())
            .limit(self._settings.butler_history_limit)
        )
        return result.scalars().all()

    async def _active_context(
        self, session: AsyncSession, user_id: uuid.UUID, today: date
    ) -> Sequence[UserContextEntryModel]:
        result = await session.execute(
            select(UserContextEntryModel)
            .where(
                UserContextEntryModel.user_id == user_id,
                UserContextEntryModel.deleted_at.is_(None),
                or_(
                    UserContextEntryModel.starts_on.is_(None),
                    UserContextEntryModel.starts_on <= today,
                ),
                or_(
                    UserContextEntryModel.ends_on.is_(None), UserContextEntryModel.ends_on >= today
                ),
            )
            .order_by(UserContextEntryModel.created_at.desc())
            .limit(self._settings.butler_user_context_limit)
        )
        return result.scalars().all()

    async def _daily_plan(
        self, session: AsyncSession, user_id: uuid.UUID, plan_date: date
    ) -> DailyPlanModel | None:
        return await self._scalar(
            session,
            select(DailyPlanModel).where(
                DailyPlanModel.user_id == user_id,
                DailyPlanModel.plan_date == plan_date,
                DailyPlanModel.deleted_at.is_(None),
            ),
        )

    async def _events_for_day(
        self, session: AsyncSession, user_id: uuid.UUID, event_date: date
    ) -> Sequence[DailyEventModel]:
        result = await session.execute(
            select(DailyEventModel)
            .where(
                DailyEventModel.user_id == user_id,
                DailyEventModel.event_date == event_date,
                DailyEventModel.deleted_at.is_(None),
            )
            .order_by(
                DailyEventModel.start_time.is_(None),
                DailyEventModel.start_time,
                DailyEventModel.sort_order,
            )
            .limit(self._settings.butler_event_limit)
        )
        return result.scalars().all()

    async def _create_daily_event(self, state: ButlerState) -> ButlerStateUpdate:
        decision = self._decision(state)
        if not decision.title:
            return self._needs_follow_up("What should I call this event?")

        event_date = decision.event_date or state["today"]
        async with self._session_factory() as session, session.begin():
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
            )
            session.add(event)
            changed = ChangedEntity(type="daily_event", id=event.id)

        when = self._format_event_time(event_date, decision.start_time)
        return {
            "result": ButlerResult(
                response=f"Done. I added {decision.title} {when}.",
                changed_entities=[changed],
            )
        }

    async def _update_daily_event(self, state: ButlerState) -> ButlerStateUpdate:
        decision = self._decision(state)
        async with self._session_factory() as session, session.begin():
            event = await self._resolve_event(session, state)
            if event is None:
                return self._needs_follow_up("Which event should I update?")

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
                return self._needs_follow_up("What should I change about it?")

            event.version += 1
            changed = ChangedEntity(type="daily_event", id=event.id)

        return {
            "result": ButlerResult(
                response=f"Done. I updated {event.title}.",
                changed_entities=[changed],
            )
        }

    async def _skip_daily_event(self, state: ButlerState) -> ButlerStateUpdate:
        async with self._session_factory() as session, session.begin():
            event = await self._resolve_event(session, state)
            if event is None:
                return self._needs_follow_up("Which event should I skip?")
            if event.status == "skipped":
                return {"result": ButlerResult(response=f"{event.title} is already skipped.")}
            event.status = "skipped"
            event.version += 1
            changed = ChangedEntity(type="daily_event", id=event.id)

        return {
            "result": ButlerResult(
                response=f"Done. I skipped {event.title}.",
                changed_entities=[changed],
            )
        }

    async def _remember_user_context(self, state: ButlerState) -> ButlerStateUpdate:
        decision = self._decision(state)
        if not decision.context_content:
            return self._needs_follow_up("What would you like me to remember?")

        context_type = decision.context_type or "reference"
        async with self._session_factory() as session, session.begin():
            entry = UserContextEntryModel(
                id=uuid.uuid4(),
                user_id=state["user_id"],
                context_type=context_type,
                content=decision.context_content,
            )
            session.add(entry)
            changed = ChangedEntity(type="user_context", id=entry.id)

        return {
            "result": ButlerResult(
                response="Got it. I will remember that.",
                changed_entities=[changed],
            )
        }

    async def _get_or_create_plan(
        self, session: AsyncSession, user_id: uuid.UUID, plan_date: date
    ) -> DailyPlanModel:
        plan = await self._daily_plan(session, user_id, plan_date)
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
        decision = self._decision(state)
        if decision.target_event_id is not None:
            return await self._scalar(
                session,
                select(DailyEventModel).where(
                    DailyEventModel.id == decision.target_event_id,
                    DailyEventModel.user_id == state["user_id"],
                    DailyEventModel.deleted_at.is_(None),
                ),
            )

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
        )
        matches = list(result.scalars())
        return matches[0] if len(matches) == 1 else None

    def _needs_follow_up(self, question: str) -> ButlerStateUpdate:
        return {"result": ButlerResult(response=question, requires_follow_up=True)}

    def _context(self, state: ButlerState) -> ButlerContext:
        context = state.get("context")
        if context is None:
            raise ButlerError("Butler graph state is missing context")
        return context

    def _decision(self, state: ButlerState) -> ButlerDecision:
        decision = state.get("decision")
        if decision is None:
            raise ButlerError("Butler graph state is missing decision")
        return decision

    def _result(self, state: ButlerState) -> ButlerResult:
        result = state.get("result")
        if result is None:
            raise ButlerError("Butler graph state is missing result")
        return result

    def _summarize_events(self, events: Sequence[ContextEvent]) -> str:
        if not events:
            return "You do not have anything on your plan for today yet."
        parts = [
            f"{event.title} at {event.start_time.strftime('%H:%M')}"
            if event.start_time
            else event.title
            for event in events
        ]
        return "Today you have " + ", ".join(parts) + "."

    def _context_event(self, row: DailyEventModel) -> ContextEvent:
        return ContextEvent(
            id=row.id,
            title=row.title,
            description=row.description,
            event_type=row.event_type,
            status=row.status,
            event_date=row.event_date,
            start_time=row.start_time,
            end_time=row.end_time,
            duration_minutes=row.duration_minutes,
        )

    def _format_event_time(self, event_date: date, start_time: time | None) -> str:
        if start_time is None:
            return f"on {event_date.isoformat()}"
        return f"on {event_date.isoformat()} at {start_time.strftime('%H:%M')}"

    async def _scalar[T](self, session: AsyncSession, statement: Select[tuple[T]]) -> T | None:
        result = await session.execute(statement)
        return result.scalar_one_or_none()
