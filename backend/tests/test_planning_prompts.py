import unittest
from datetime import date, datetime
from unittest.mock import AsyncMock
from zoneinfo import ZoneInfo

from app.application.planning.contracts import (
    DayPlanningInput,
    GoodNightSummaryDraft,
    GoodNightSummaryInput,
    MorningBriefDraft,
)
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider
from app.infrastructure.ai.prompts import (
    BUTLER_INTERACTION_INSTRUCTIONS,
    DAY_PLANNING_INSTRUCTIONS,
    GOOD_NIGHT_SUMMARY_INSTRUCTIONS,
    MORNING_BRIEF_INSTRUCTIONS,
    TEMPORAL_CONTEXT_INSTRUCTIONS,
)


class PlanningPromptTests(unittest.IsolatedAsyncioTestCase):
    async def test_actual_generation_uses_updated_prompts_and_local_now(self):
        provider = OpenAIButlerProvider(api_key="", model="test-model")
        provider._parse = AsyncMock()
        now = datetime(2026, 10, 13, 17, 30, tzinfo=ZoneInfo("Asia/Bangkok"))
        planning = DayPlanningInput(
            now=now,
            target_date=date(2026, 10, 14),
            weekday="Wednesday",
            timezone="Asia/Bangkok",
            user_context=[],
            upcoming_events=[],
            known_events=[],
        )
        await provider.plan_day(planning)
        self.assertEqual(
            provider._parse.call_args.kwargs["instructions"], DAY_PLANNING_INSTRUCTIONS
        )
        self.assertIn(
            "2026-10-13T17:30:00+07:00", provider._parse.call_args.kwargs["payload"]["now"]
        )
        await provider.compose_morning_brief(planning, [])
        self.assertEqual(
            provider._parse.call_args.kwargs["instructions"], MORNING_BRIEF_INSTRUCTIONS
        )
        self.assertIn("do not open with Good morning", MORNING_BRIEF_INSTRUCTIONS)
        summary = GoodNightSummaryInput(
            now=now,
            summary_date=now.date(),
            tomorrow_date=date(2026, 10, 14),
            timezone="Asia/Bangkok",
            user_context=[],
            events=[],
        )
        await provider.compose_good_night_summary(summary)
        self.assertEqual(
            provider._parse.call_args.kwargs["instructions"], GOOD_NIGHT_SUMMARY_INSTRUCTIONS
        )
        self.assertIn("do not open with Good evening", GOOD_NIGHT_SUMMARY_INSTRUCTIONS)
        self.assertIn(TEMPORAL_CONTEXT_INSTRUCTIONS, BUTLER_INTERACTION_INSTRUCTIONS)
        self.assertIn(TEMPORAL_CONTEXT_INSTRUCTIONS, DAY_PLANNING_INSTRUCTIONS)

    def test_greeting_guard_preserves_substantive_content(self):
        self.assertEqual(
            MorningBriefDraft(content="Good morning, sir! Your meeting is at 9.").content,
            "Your meeting is at 9.",
        )
        self.assertEqual(
            GoodNightSummaryDraft(content="Good night! Tomorrow begins at 8.").content,
            "Tomorrow begins at 8.",
        )
        self.assertEqual(
            MorningBriefDraft(content="Your first meeting is at 9.").content,
            "Your first meeting is at 9.",
        )
