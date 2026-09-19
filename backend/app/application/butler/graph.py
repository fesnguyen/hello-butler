from __future__ import annotations

from collections.abc import Awaitable, Callable

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.application.butler.actions import DailyEventActions, UserContextActions
from app.application.butler.context import ButlerContextLoader
from app.application.butler.contracts import (
    ButlerAIProvider,
    ButlerMutationRejectedError,
    ButlerResult,
    Intent,
    RequestedAction,
)
from app.application.butler.history import ButlerHistoryWriter
from app.application.butler.state import (
    ButlerState,
    ButlerStateUpdate,
    canonical_response_from,
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
        graph.add_node("interaction", self._interaction)
        graph.add_node("apply_action", self._apply_action)
        graph.add_node("complete_without_mutation", self._complete_without_mutation)
        graph.add_node("save_history", self._save_history)

        graph.add_edge(START, "load_context")
        graph.add_edge("load_context", "interaction")
        graph.add_conditional_edges(
            "interaction",
            self._route,
            {
                "command": "apply_action",
                "query": "complete_without_mutation",
                "clarify": "complete_without_mutation",
            },
        )
        graph.add_edge("apply_action", "save_history")
        graph.add_edge("complete_without_mutation", "save_history")
        graph.add_edge("save_history", END)
        return graph.compile()

    async def _load_context(self, state: ButlerState) -> ButlerStateUpdate:
        return {"context": await self._context_loader.load(state["user_id"], state["today"])}

    async def _interaction(self, state: ButlerState) -> ButlerStateUpdate:
        proposal = await self._ai_provider.interact(
            request_id=state.get("request_id"),
            message=state["message"] or None,
            audio_path=state["audio_path"],
            audio_mime_type=state["audio_mime_type"],
            interaction_mode=state["interaction_mode"],
            now=state["now"].isoformat(),
            timezone=state["timezone"],
            context=context_from(state),
        )
        return {
            "message": proposal.user_message_text,
            "decision": proposal.decision,
            "proposal": proposal,
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
            "delete_daily_event": self._event_actions.delete,
            "remember_user_context": self._context_actions.remember,
            "update_user_context": self._context_actions.update,
            "mutate_upcoming_event": self._context_actions.mutate_upcoming,
        }
        handler = handlers.get(decision_from(state).requested_action)
        if handler is None:
            raise ButlerMutationRejectedError("Command proposed no supported mutation")
        return await handler(state)

    async def _complete_without_mutation(self, state: ButlerState) -> ButlerStateUpdate:
        decision = decision_from(state)
        return {
            "result": ButlerResult(
                response=canonical_response_from(state),
                requires_follow_up=(
                    decision.intent == "clarify" or bool(decision.missing_information)
                ),
            )
        }

    async def _save_history(self, state: ButlerState) -> ButlerStateUpdate:
        await self._history_writer.save(state)
        return {}
