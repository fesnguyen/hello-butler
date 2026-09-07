import json
from typing import TypeVar

from openai import APIError, AsyncOpenAI
from pydantic import BaseModel

from app.application.butler import ButlerAIUnavailableError, ButlerContext, ButlerDecision
from app.application.planning.contracts import (
    DayPlanningInput,
    GoodNightSummaryDraft,
    GoodNightSummaryInput,
    MorningBriefDraft,
    PlannedDayProposal,
)

ModelT = TypeVar("ModelT", bound=BaseModel)


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

    async def plan_day(self, planning_input: DayPlanningInput) -> PlannedDayProposal:
        return await self._parse(
            instructions=DAY_PLANNING_INSTRUCTIONS,
            payload=planning_input.model_dump(mode="json"),
            text_format=PlannedDayProposal,
        )

    async def compose_morning_brief(
        self, planning_input: DayPlanningInput, final_events: list[dict[str, object]]
    ) -> MorningBriefDraft:
        return await self._parse(
            instructions=MORNING_BRIEF_INSTRUCTIONS,
            payload={
                "planning_input": planning_input.model_dump(mode="json"),
                "final_events": final_events,
            },
            text_format=MorningBriefDraft,
        )

    async def compose_good_night_summary(
        self, summary_input: GoodNightSummaryInput
    ) -> GoodNightSummaryDraft:
        return await self._parse(
            instructions=GOOD_NIGHT_SUMMARY_INSTRUCTIONS,
            payload=summary_input.model_dump(mode="json"),
            text_format=GoodNightSummaryDraft,
        )

    async def _parse(
        self,
        *,
        instructions: str,
        payload: object,
        text_format: type[ModelT],
    ) -> ModelT:
        if self._client is None:
            raise ButlerAIUnavailableError("OpenAI API key is not configured")
        try:
            response = await self._client.responses.parse(
                model=self._model,
                instructions=instructions,
                input=json.dumps(payload, ensure_ascii=True),
                text_format=text_format,
                store=False,
            )
        except APIError as exc:
            raise ButlerAIUnavailableError("AI provider request failed") from exc
        if response.output_parsed is None:
            raise ButlerAIUnavailableError("AI provider returned no structured output")
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

When remembering temporary or one-time context, populate context_starts_on and
context_ends_on whenever the applicable date or range can be resolved.
""".strip()

DAY_PLANNING_INSTRUCTIONS = """
You prepare one practical day for Personal Butler. Return only structured output.
The application controls the target date and persistence.

Use relevant reference, recurring, temporary, and one-time context. Respect the
provided date ranges and weekday. Treat known_events as protected commitments:
plan around them and never duplicate, replace, or move them. Explicit commitments
and preferences outrank suggestions. Leave useful empty time instead of filling
the entire day. Propose only concrete events that improve the day. Daytime events
should normally have speak_aloud=false. Choose a calm morning_brief_time that fits
the supplied wake or morning routine; otherwise leave it null so the configured
default applies.
""".strip()

MORNING_BRIEF_INSTRUCTIONS = """
Write a concise, natural Morning Brief for spoken Android text-to-speech. Base it
only on final_events, which is the day that will actually be shown. Sound like a
calm personal Butler, not a database dump. Mention the useful morning sequence,
important commitments, unusual circumstances, reminders, and selected later
events. Do not list every field or invent events. Return only structured output.
""".strip()

GOOD_NIGHT_SUMMARY_INSTRUCTIONS = """
Write a concise, natural Good Night Summary for spoken Android text-to-speech.
Use the actual event status and changes supplied for the completed day. Mention
useful accomplishments, skipped or unfinished commitments without judgment, and
tomorrow context only when it is present. Be calm and supportive, not medical or
authoritative. Do not invent activity or claim an event happened when its status
does not show that. Return only structured output.
""".strip()
