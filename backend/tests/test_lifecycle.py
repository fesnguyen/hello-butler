"""SQLite lifecycle regressions; PostgreSQL locking and real FCM need device/integration QA."""

import asyncio
import tempfile
import unittest
import uuid
from datetime import datetime, time, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.application.butler.contracts import (
    ButlerDecision,
    ButlerInteractionProposal,
    ButlerMutationRejectedError,
)
from app.application.butler.service import ButlerService
from app.application.planning.contracts import (
    GoodNightSummaryDraft,
    MorningBriefDraft,
    PlannedDayProposal,
)
from app.application.planning.evening import EveningPreparationService
from app.application.planning.service import DayPlanningService
from app.application.push.changes import DailyPlanChanges
from app.application.push.service import PushService
from app.application.sync.contracts import DailyEventMutation, EventSyncOperation
from app.application.sync.service import DailyEventSyncService
from app.core.config import Settings
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import (
    ConversationMessageModel,
    DailyEventModel,
    DailyPlanModel,
    UserContextEntryModel,
    UserModel,
)
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"  # Test dialect only; production continues to use PostgreSQL JSONB.


class LifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'test.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.user = uuid.uuid4()
        self.settings = Settings(jwt_secret="test-only-secret-32-characters-long")
        self.today = datetime.now(self.settings.timezone).date()
        self.provider = AsyncMock()
        self.provider.send_data.return_value = set()
        self.push = PushService(self.sessions, self.provider)
        self.changes = DailyPlanChanges(self.sessions, self.push)
        self.sync = DailyEventSyncService(self.changes)
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=self.user))
        await self.push.register(self.user, "phone-a-registration-token")
        await self.push.register(self.user, "phone-b-registration-token")

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    def operation(self, action="create", *, event_id=None, version=0, hour=18):
        event_id = event_id or uuid.uuid4()
        return EventSyncOperation(
            operation_id=uuid.uuid4(),
            action=action,
            event_id=event_id,
            base_version=version,
            event=None
            if action == "delete"
            else DailyEventMutation(
                id=event_id,
                event_date=self.today,
                title="Exercise",
                event_type="exercise",
                start_time=time(hour),
            ),
        )

    def planner_ai(self):
        ai = AsyncMock()
        ai.plan_day.return_value = PlannedDayProposal(events=[])
        ai.compose_morning_brief.return_value = MorningBriefDraft(content="Your prepared morning.")
        ai.compose_good_night_summary.return_value = GoodNightSummaryDraft(
            content="Your actual day."
        )
        return ai

    @staticmethod
    def interaction(
        message: str, decision: ButlerDecision, response: str
    ) -> ButlerInteractionProposal:
        return ButlerInteractionProposal(
            thought="The response follows from the supplied decision.",
            user_message_text=message,
            decision=decision,
            response_text=response,
        )

    async def test_push_observes_committed_data_and_only_sends_hint_to_both_devices(self):
        operation = self.operation()

        async def receive(tokens, data):
            self.assertEqual(
                set(tokens), {"phone-a-registration-token", "phone-b-registration-token"}
            )
            self.assertEqual(data, {"type": "daily_plan_changed"})
            async with self.sessions() as session:
                self.assertIsNotNone(await session.get(DailyEventModel, operation.event_id))
            return set()

        self.provider.send_data.side_effect = receive
        await self.sync.apply(self.user, [operation])
        self.provider.send_data.assert_awaited_once()

    async def test_rollback_after_flush_does_not_publish(self):
        with self.assertRaises(RuntimeError):
            async with self.changes.transaction(self.user) as session:
                session.add(DailyPlanModel(user_id=self.user, plan_date=self.today))
                await session.flush()
                raise RuntimeError("rollback")
        self.provider.send_data.assert_not_awaited()
        async with self.sessions() as session:
            self.assertEqual(list((await session.execute(select(DailyPlanModel))).scalars()), [])

    async def test_push_and_token_lookup_failure_do_not_fail_committed_mutation(self):
        for failure in ("provider", "lookup"):
            with self.subTest(failure=failure):
                target = self.provider if failure == "provider" else self.push
                method = "send_data" if failure == "provider" else "daily_plan_changed"
                with patch.object(target, method, AsyncMock(side_effect=RuntimeError("offline"))):
                    result = await self.sync.apply(self.user, [self.operation()])
                self.assertEqual(result.results[0].status, "applied")
                async with self.sessions() as session:
                    self.assertIsNotNone(
                        await session.get(DailyEventModel, result.results[0].event.id)
                    )

    async def test_push_timeout_does_not_fail_the_committed_operation(self):
        async def slow_push(_):
            await asyncio.sleep(60)

        changes = DailyPlanChanges(self.sessions, self.push, timeout_seconds=0.01)
        with patch.object(self.push, "daily_plan_changed", side_effect=slow_push):
            result = await DailyEventSyncService(changes).apply(self.user, [self.operation()])
        self.assertEqual(result.results[0].status, "applied")

    async def test_duplicate_and_conflict_return_canonical_without_extra_push(self):
        operation = self.operation()
        await self.sync.apply(self.user, [operation])
        duplicate = await self.sync.apply(self.user, [operation])
        self.assertEqual(duplicate.results[0].status, "duplicate")
        moved = self.operation("delay", event_id=operation.event_id, version=1, hour=19)
        await self.sync.apply(self.user, [moved])
        stale = self.operation("delay", event_id=operation.event_id, version=1, hour=20)
        conflict = (await self.sync.apply(self.user, [stale])).results[0]
        self.assertEqual(conflict.status, "conflict")
        self.assertEqual(conflict.event.start_time, time(19))
        self.assertEqual(conflict.event.version, 2)
        self.assertEqual(self.provider.send_data.await_count, 2)

    async def test_sync_then_butler_reasons_from_updated_event_and_returns_both_dates(self):
        created = self.operation()
        await self.sync.apply(self.user, [created])
        await self.sync.apply(
            self.user, [self.operation("delay", event_id=created.event_id, version=1, hour=19)]
        )
        ai = AsyncMock()
        target_date = self.today + timedelta(days=3)

        async def interact(**request):
            self.assertEqual(request["context"].relevant_events[0].start_time, time(19))
            return self.interaction(
                "Move it",
                ButlerDecision(
                    intent="command",
                    requested_action="update_daily_event",
                    target_event_id=created.event_id,
                    event_date=target_date,
                ),
                "Done. I moved Exercise.",
            )

        ai.interact.side_effect = interact
        service = ButlerService(
            settings=self.settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=self.changes,
        )
        result = await service.handle(user_id=self.user, interaction_mode="talk", message="Move it")
        self.assertEqual(result.changed_entities[0].plan_dates, [self.today, target_date])
        self.assertEqual(self.provider.send_data.await_count, 3)

    async def test_butler_change_publishes_even_if_history_later_fails(self):
        ai = AsyncMock()
        ai.interact.return_value = self.interaction(
            "Add exercise",
            ButlerDecision(
                intent="command", requested_action="create_daily_event", title="Exercise"
            ),
            "Done. I added Exercise.",
        )
        service = ButlerService(
            settings=self.settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=self.changes,
        )
        with (
            patch(
                "app.application.butler.history.ButlerHistoryWriter.save",
                side_effect=RuntimeError("history"),
            ),
            self.assertRaises(RuntimeError),
        ):
            await service.handle(
                user_id=self.user, interaction_mode="order", message="Add exercise"
            )
        self.provider.send_data.assert_awaited_once()

    async def test_butler_skip_uses_model_response_unchanged(self):
        created = self.operation()
        await self.sync.apply(self.user, [created])
        ai = AsyncMock()
        canonical = "Of course. I skipped Exercise for today."
        ai.interact.return_value = self.interaction(
            "Skip exercise",
            ButlerDecision(
                intent="command",
                requested_action="skip_daily_event",
                target_event_id=created.event_id,
            ),
            canonical,
        )
        service = ButlerService(
            settings=self.settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=self.changes,
        )

        completed = await service.handle(
            user_id=self.user, interaction_mode="order", message="Skip exercise"
        )

        async with self.sessions() as session:
            event = await session.get(DailyEventModel, created.event_id)
        self.assertEqual(event.status, "skipped")
        self.assertEqual(completed.response, canonical)

    async def test_text_and_audio_provider_use_shared_graph_actions_and_history(self):
        for has_audio in (False, True):
            with self.subTest(has_audio=has_audio):
                title = "Audio exercise" if has_audio else "Text exercise"
                utterance = f"Add {title}"
                canonical = f"Done. I added {title}."
                proposal = self.interaction(
                    utterance,
                    ButlerDecision(
                        intent="command", requested_action="create_daily_event", title=title
                    ),
                    canonical,
                )
                call = SimpleNamespace(
                    type="function",
                    function=SimpleNamespace(
                        name="submit_butler_interaction", arguments=proposal.model_dump_json()
                    ),
                )
                create = AsyncMock(
                    return_value=SimpleNamespace(
                        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=[call]))]
                    )
                )
                ai = OpenAIButlerProvider(
                    api_key="",
                    model=self.settings.openai_model,
                    audio_model=self.settings.butler_audio_model,
                )
                ai._client = SimpleNamespace(
                    chat=SimpleNamespace(completions=SimpleNamespace(create=create))
                )
                service = ButlerService(
                    settings=self.settings,
                    session_factory=self.sessions,
                    ai_provider=ai,
                    changes=self.changes,
                )
                source = Path(self.directory.name) / "input.ogg"
                source.write_bytes(b"OggS" + b"0" * 24 + b"OpusHead")
                with patch.object(ai, "_wav_audio", AsyncMock(return_value=b"input-wav")) as decode:
                    result = await service.handle(
                        user_id=self.user,
                        interaction_mode="order",
                        message=None if has_audio else utterance,
                        audio_path=source if has_audio else None,
                        audio_mime_type="audio/ogg" if has_audio else None,
                    )
                self.assertEqual(
                    create.await_args.kwargs["model"],
                    self.settings.butler_audio_model if has_audio else self.settings.openai_model,
                )
                self.assertEqual(decode.await_count, int(has_audio))
                self.assertEqual(
                    (result.user_message_text, result.response), (utterance, canonical)
                )
                async with self.sessions() as session:
                    event = await session.get(DailyEventModel, result.changed_entities[0].id)
                    self.assertEqual((event.title, event.origin), (title, "user"))
                    messages = (await session.scalars(select(ConversationMessageModel))).all()
                    self.assertIn(
                        ("user", utterance), [(row.role, row.content) for row in messages]
                    )
                    self.assertIn(
                        ("butler", canonical), [(row.role, row.content) for row in messages]
                    )

    async def test_butler_updates_user_context(self):
        context_id = uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(
                UserContextEntryModel(
                    id=context_id,
                    user_id=self.user,
                    context_type="reference",
                    content="I prefer evening exercise.",
                )
            )
        ai = AsyncMock()
        ai.interact.return_value = self.interaction(
            "Actually I prefer morning exercise",
            ButlerDecision(
                intent="command",
                requested_action="update_user_context",
                target_context_id=context_id,
                context_content="I prefer morning exercise.",
            ),
            "Got it. I'll remember that you prefer morning exercise.",
        )
        service = ButlerService(
            settings=self.settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=self.changes,
        )

        await service.handle(
            user_id=self.user,
            interaction_mode="talk",
            message="Actually I prefer morning exercise",
        )

        async with self.sessions() as session:
            entry = await session.get(UserContextEntryModel, context_id)
        self.assertEqual(entry.content, "I prefer morning exercise.")

    async def test_query_and_clarification_apply_no_mutation(self):
        for intent, follow_up in (("query", False), ("clarify", True)):
            with self.subTest(intent=intent):
                ai = AsyncMock()
                ai.interact.return_value = self.interaction(
                    "Hello",
                    ButlerDecision(intent=intent, requested_action="none"),
                    "How can I help?" if intent == "query" else "Sorry, what did you mean?",
                )
                service = ButlerService(
                    settings=self.settings,
                    session_factory=self.sessions,
                    ai_provider=ai,
                    changes=self.changes,
                )
                completed = await service.handle(
                    user_id=self.user, interaction_mode="talk", message="Hello"
                )
                self.assertEqual(completed.requires_follow_up, follow_up)

        async with self.sessions() as session:
            events = list((await session.scalars(select(DailyEventModel))).all())
        self.assertEqual(events, [])

    async def test_invalid_mutation_cannot_persist_false_success(self):
        ai = AsyncMock()
        ai.interact.return_value = self.interaction(
            "Move the missing meeting",
            ButlerDecision(
                intent="command",
                requested_action="update_daily_event",
                target_event_id=uuid.uuid4(),
                start_time=time(15),
            ),
            "Done. I moved your meeting.",
        )
        service = ButlerService(
            settings=self.settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=self.changes,
        )

        with self.assertRaises(ButlerMutationRejectedError):
            await service.handle(
                user_id=self.user,
                interaction_mode="order",
                message="Move the missing meeting",
            )

        async with self.sessions() as session:
            messages = list((await session.scalars(select(ConversationMessageModel))).all())
        self.assertEqual(messages, [])

    async def test_preparation_pushes_and_user_owned_event_survives_regeneration(self):
        planner = DayPlanningService(self.settings, self.sessions, self.planner_ai(), self.changes)
        prepared = await planner.prepare(self.user, self.today)
        self.provider.send_data.assert_awaited_once()
        brief_id = prepared.events[0].id
        # An explicit user skip must survive the planner ownership transition.
        await self.sync.apply(self.user, [self.operation("skip", event_id=brief_id, version=1)])
        await planner.prepare(self.user, self.today)
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, brief_id)
            self.assertEqual(
                (event.origin, event.planner_key, event.status), ("user", None, "skipped")
            )

    async def test_every_direct_mutation_publishes_after_commit(self):
        created = self.operation()
        await self.sync.apply(self.user, [created])
        for version, action in enumerate(
            ["edit", "complete", "skip", "delay", "cancel", "delete"], 1
        ):
            result = await self.sync.apply(
                self.user, [self.operation(action, event_id=created.event_id, version=version)]
            )
            self.assertEqual(result.results[0].status, "applied")
            self.assertEqual(result.results[0].event.origin, "user")
        self.assertEqual(self.provider.send_data.await_count, 7)
        self.assertIsNotNone(result.results[0].event.deleted_at)

    async def test_evening_reads_actual_day_before_preparing_tomorrow(self):
        from contextlib import asynccontextmanager

        created = self.operation()
        await self.sync.apply(self.user, [created])
        await self.sync.apply(
            self.user, [self.operation("complete", event_id=created.event_id, version=1)]
        )
        self.provider.send_data.reset_mock()
        ai = self.planner_ai()
        order = []

        async def summary(value):
            order.append("summary")
            self.assertEqual(value.events[0].status, "completed")
            return GoodNightSummaryDraft(content="Exercise completed.")

        async def plan(value):
            order.append("tomorrow")
            self.assertEqual(value.target_date, self.today + timedelta(days=1))
            return PlannedDayProposal(events=[])

        ai.compose_good_night_summary.side_effect = summary
        ai.plan_day.side_effect = plan
        service = EveningPreparationService(self.settings, self.sessions, ai, self.changes)

        @asynccontextmanager
        async def no_lock(_):
            yield

        with patch.object(service._lock, "hold", no_lock):
            await service.prepare(self.user, self.today)
        self.assertEqual(order, ["summary", "tomorrow"])
        self.assertEqual(self.provider.send_data.await_count, 2)
        async with self.sessions() as session:
            rows = list((await session.execute(select(DailyEventModel))).scalars())
            self.assertEqual(
                {row.event_type for row in rows},
                {"exercise", "morning_brief", "good_night_summary"},
            )

    async def test_evening_partial_commit_still_publishes(self):
        service = EveningPreparationService(
            self.settings, self.sessions, self.planner_ai(), self.changes
        )
        # PostgreSQL advisory lock is exercised in the documented integration gate.
        from contextlib import asynccontextmanager

        @asynccontextmanager
        async def no_lock(_):
            yield

        with (
            patch.object(service._lock, "hold", no_lock),
            patch.object(service, "_persist_summary", side_effect=RuntimeError("summary")),
            self.assertRaises(RuntimeError),
        ):
            await service.prepare(self.user, self.today)
        self.provider.send_data.assert_awaited_once()  # Tomorrow committed before summary failed.

    async def test_unregister_removes_only_the_authenticated_users_device(self):
        await self.push.unregister(uuid.uuid4(), "phone-b-registration-token")
        await self.push.daily_plan_changed(self.user)
        self.assertEqual(len(self.provider.send_data.call_args.args[0]), 2)
        await self.push.unregister(self.user, "phone-b-registration-token")
        await self.push.daily_plan_changed(self.user)
        self.assertEqual(self.provider.send_data.call_args.args[0], ["phone-a-registration-token"])


if __name__ == "__main__":
    unittest.main()
