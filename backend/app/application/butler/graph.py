from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.application.butler.actions import DailyEventActions, UserContextActions
from app.application.butler.context import ButlerContextLoader
from app.application.butler.contracts import (
    ButlerAIProvider,
    ButlerResult,
    ContextEvent,
    Intent,
    RequestedAction,
)
from app.application.butler.history import ButlerHistoryWriter
from app.application.butler.state import (
    ButlerState,
    ButlerStateUpdate,
    context_from,
    decision_from,
)


class ButlerGraph:
    def __init__(
        self,
        *,
        ai_provider: ButlerAIProvider,
        context_loader: ButlerContextLoader,
        event_actions: DailyEventActions,
        context_actions: UserContextActions,
        history_writer: ButlerHistoryWriter,
    ) -> None:
        self._ai_provider = ai_provider
        self._context_loader = context_loader
        self._event_actions = event_actions
        self._context_actions = context_actions
        self._history_writer = history_writer
        self.compiled = self._build()

    def _build(self) -> CompiledStateGraph[ButlerState, None, ButlerState, ButlerState]:
        graph = StateGraph(ButlerState)
        graph.add_node("load_context", self._load_context)
        graph.add_node("understand_request", self._understand_request)
        graph.add_node("apply_action", self._apply_action)
        graph.add_node("build_query_result", self._build_query_result)
        graph.add_node("build_clarification", self._build_clarification)
        graph.add_node("save_history", self._save_history)

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

    async def _load_context(self, state: ButlerState) -> ButlerStateUpdate:
        return {"context": await self._context_loader.load(state["user_id"], state["today"])}

    async def _understand_request(self, state: ButlerState) -> ButlerStateUpdate:
        understanding = await self._ai_provider.understand(
            message=state["message"] or None,
            audio_path=state["audio_path"],
            audio_mime_type=state["audio_mime_type"],
            interaction_mode=state["interaction_mode"],
            now=state["now"].isoformat(),
            timezone=state["timezone"],
            context=context_from(state),
        )
        return {
            "message": understanding.user_message_text,
            "decision": understanding.decision,
        }

    @staticmethod
    def _route(state: ButlerState) -> Intent:
        decision = decision_from(state)
        if decision.intent == "command" and decision.missing_information:
            return "clarify"
        return decision.intent

    async def _apply_action(self, state: ButlerState) -> ButlerStateUpdate:
        handlers: dict[RequestedAction, Callable[[ButlerState], Awaitable[ButlerStateUpdate]]] = {
            "create_daily_event": self._event_actions.create,
            "update_daily_event": self._event_actions.update,
            "skip_daily_event": self._event_actions.skip,
            "remember_user_context": self._context_actions.remember,
            "answer_today_events": self._build_query_result,
            "none": self._build_clarification,
        }
        handler = handlers.get(decision_from(state).requested_action, self._build_clarification)
        return await handler(state)

    async def _build_query_result(self, state: ButlerState) -> ButlerStateUpdate:
        answer = decision_from(state).answer or self._summarize_events(
            context_from(state).relevant_events
        )
        return {"result": ButlerResult(response=answer)}

    async def _build_clarification(self, state: ButlerState) -> ButlerStateUpdate:
        question = decision_from(state).clarification_question or "Sorry, what did you mean?"
        return {"result": ButlerResult(response=question, requires_follow_up=True)}

    async def _save_history(self, state: ButlerState) -> ButlerStateUpdate:
        await self._history_writer.save(state)
        return {}

    @staticmethod
    def _summarize_events(events: Sequence[ContextEvent]) -> str:
        if not events:
            return "You do not have anything on your plan for today yet."
        parts = [
            f"{event.title} at {event.start_time.strftime('%H:%M')}"
            if event.start_time
            else event.title
            for event in events
        ]
        return "Today you have " + ", ".join(parts) + "."
