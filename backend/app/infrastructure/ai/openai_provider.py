import json

from openai import APIError, AsyncOpenAI

from app.application.butler import ButlerAIUnavailableError, ButlerContext, ButlerDecision


class OpenAIButlerProvider:
    def __init__(self, *, api_key: str, model: str) -> None:
        self._api_key = api_key
        self._model = model
        self._client = AsyncOpenAI(api_key=api_key) if api_key else None

    async def understand(
        self,
        *,
        message: str,
        interaction_mode: str,
        now: str,
        timezone: str,
        context: ButlerContext,
    ) -> ButlerDecision:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")

        payload = {
            "message": message,
            "interaction_mode": interaction_mode,
            "now": now,
            "timezone": timezone,
            "context": context.model_dump(mode="json"),
        }
        try:
            response = await self._client.responses.parse(
                model=self._model,
                instructions=BUTLER_DECISION_INSTRUCTIONS,
                input=json.dumps(payload, ensure_ascii=True),
                text_format=ButlerDecision,
                store=False,
            )
        except APIError as exc:
            raise ButlerAIUnavailableError("AI provider request failed") from exc

        if response.output_parsed is None:
            raise ButlerAIUnavailableError("AI provider returned no structured decision")
        return response.output_parsed


BUTLER_DECISION_INSTRUCTIONS = """
You are the reasoning layer for Personal Butler.

Return only the requested structured output. Do not invent persistence changes
outside the supported action list. The application code, not you, applies all
database mutations.

Interpret interaction_mode only as user expectation: order means the user wants
the request handled if possible, talk means the user is actively conversing.
Semantic intent is separate and must be one of command, query, clarify.

Supported actions:
- create_daily_event: create a concrete event or reminder
- update_daily_event: update or move an existing event
- skip_daily_event: mark an existing event skipped
- remember_user_context: remember a simple user fact or preference
- answer_today_events: answer a query about today's relevant events
- none: use when clarifying or answering generally

Use the supplied now and timezone when resolving relative dates and times.
For commands, include all deterministic fields needed for the action. If the
target event or required date/time/title cannot be safely resolved from the
message and recent history/context, set intent=clarify and ask one concise
clarification question.
""".strip()
