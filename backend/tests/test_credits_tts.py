import asyncio
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.application.butler.contracts import ButlerResult, ButlerSpeech
from app.application.butler.requests import ButlerRequestService
from app.application.push.changes import DailyPlanChanges
from app.application.speech import SpeechService
from app.core.config import Settings
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import (
    ButlerRequestModel,
    DailyEventModel,
    DailyPlanModel,
    UserModel,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"


class SharedSpeechTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'test.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.settings = Settings(
            jwt_secret="test-only-secret-32-characters-long", butler_openai_tts_credit_cost=1
        )
        self.openai, self.kokoro = AsyncMock(), AsyncMock()
        self.speech = SpeechService(self.settings, self.sessions, self.openai, self.kokoro)
        self.speech._store = AsyncMock(return_value=Path("response.ogg"))
        self.kokoro.synthesize.return_value = ButlerSpeech(
            audio=b"RIFF0000WAVE", mime_type="audio/wav"
        )
        self.openai.synthesize.return_value = ButlerSpeech(
            audio=b"RIFF0000WAVE", mime_type="audio/wav"
        )

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def create(self, method, credits, kind="request", event_type="morning_brief"):
        user_id, owner_id = uuid.uuid4(), uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=user_id, credits=credits, tts_method=method))
            if kind == "request":
                session.add(
                    ButlerRequestModel(
                        id=owner_id,
                        user_id=user_id,
                        input_source="text",
                        interaction_mode="talk",
                        status="completed",
                        response_text="Canonical text",
                        audio_status="pending",
                    )
                )
            else:
                plan_id = uuid.uuid4()
                from datetime import date

                session.add(DailyPlanModel(id=plan_id, user_id=user_id, plan_date=date.today()))
                session.add(
                    DailyEventModel(
                        id=owner_id,
                        user_id=user_id,
                        daily_plan_id=plan_id,
                        event_date=date.today(),
                        title="Butler summary",
                        event_type=event_type,
                        content="Prepared morning text",
                        speak_aloud=True,
                        audio_status="pending",
                    )
                )
        return user_id, owner_id

    async def test_reminder_uses_shared_voice_and_title_fallback_without_speak_flag(self):
        user_id, event_id = await self.create("OPENAI", 2, "event", "reminder")
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.speak_aloud = False
            event.content = None
            event.description = None
            event.title = "Take medication"
        self.assertIn(("event", event_id), await self.speech.pending())
        await self.speech.generate("event", event_id)
        await self.speech.generate("event", event_id)
        self.openai.synthesize.assert_awaited_once_with(text="Take medication", request_id=event_id)
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, event_id)
            user = await session.get(UserModel, user_id)
        self.assertEqual(event.audio_status, "ready")
        self.assertEqual(user.credits, 1)
        self.assertFalse(event.speak_aloud)

    async def test_reminder_preparation_reuses_pending_owner(self):
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "reminder")
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.audio_status = "unavailable"
        self.assertNotIn(("event", event_id), await self.speech.pending())
        async with self.sessions() as session:
            self.assertEqual(
                await self.speech.prepare_event(session, user_id, event_id, 1), ("pending", True)
            )
        self.kokoro.synthesize.assert_not_awaited()
        async with self.sessions() as session:
            self.assertEqual(
                await self.speech.prepare_event(session, user_id, event_id, 1),
                ("pending", False),
            )
        for requester, version, exception in (
            (uuid.uuid4(), 1, LookupError),
            (user_id, 2, ValueError),
        ):
            async with self.sessions() as session:
                with self.assertRaises(exception):
                    await self.speech.prepare_event(session, requester, event_id, version)
        await self.speech.generate("event", event_id)
        self.kokoro.synthesize.assert_awaited_once()

    def actual_audio(self, event_id, version=1):
        path = Path(self.directory.name) / f"{event_id}-{version}.ogg"
        path.write_bytes(b"OggS" + bytes(24) + b"OpusHead")
        return path

    async def test_literal_clock_retiming_invalidates_old_ready_speech(self):
        from datetime import time

        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "reminder")
        changes = DailyPlanChanges(self.sessions, AsyncMock())
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.start_time, event.content = time(14), "Meeting at 2 PM"
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.start_time, event.version = time(15), 2
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, event_id)
            self.assertEqual(event.content, "Meeting at 3 PM")
            self.assertEqual(event.audio_status, "pending")
            self.assertIsNone(event.response_audio_path)

    async def test_tts_method_update_invalidates_version(self):
        from app.application.user_settings import UserSettingsService, UserSettingsUpdate

        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "reminder")
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        async with self.sessions() as session, session.begin():
            await UserSettingsService().update(
                session, user_id, UserSettingsUpdate(tts_method="OPENAI")
            )
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, event_id)
            self.assertEqual((event.version, event.audio_status), (2, "pending"))
            self.assertIsNone(event.response_audio_path)
            with self.assertRaises(ValueError):
                await self.speech.prepare_event(session, user_id, event_id, 1)

    async def test_ready_event_rebinds_time_only_version_without_synthesis(self):
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "custom")
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        changes = DailyPlanChanges(self.sessions, AsyncMock())
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.version = 2
            event.sort_order = 9
        async with self.sessions() as session:
            self.assertEqual(
                await self.speech.prepare_event(session, user_id, event_id, 2), ("ready", False)
            )
            event = await session.get(DailyEventModel, event_id)
            self.assertEqual(Path(event.response_audio_path).name, f"{event_id}-2.ogg")
            self.assertEqual(
                Path(event.response_audio_path).read_bytes(),
                self.actual_audio(event_id).read_bytes(),
            )
        self.kokoro.synthesize.assert_awaited_once()

    async def test_canonical_reminder_creation_queues_early_and_edits_invalidate(self):
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "reminder")
        changes = DailyPlanChanges(self.sessions, AsyncMock())
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.content = None
            event.speak_aloud = False
            event.title = "Take medicine"
        self.assertIn(("event", event_id), await self.speech.pending())
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.title = "Drink water"
            event.version += 1
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, event_id)
            self.assertEqual(event.audio_status, "pending")
            self.assertIsNone(event.response_audio_path)
        self.assertIn(("event", event_id), await self.speech.pending())

    async def test_generation_discards_changed_version_and_refunds_stale_charge(self):
        user_id, event_id = await self.create("OPENAI", 2, "event")
        changes = DailyPlanChanges(self.sessions, AsyncMock())
        started, release = asyncio.Event(), asyncio.Event()

        async def synthesize(**kwargs):
            started.set()
            await release.wait()
            return ButlerSpeech(audio=b"RIFF0000WAVE", mime_type="audio/wav")

        self.openai.synthesize.side_effect = synthesize
        path = self.actual_audio(event_id)
        self.speech._store.return_value = path
        task = asyncio.create_task(self.speech.generate("event", event_id))
        await started.wait()
        async with changes.transaction(user_id) as session:
            event = await session.get(DailyEventModel, event_id)
            event.version += 1
            event.content = "New text"
        release.set()
        await task
        async with self.sessions() as session:
            event = await session.get(DailyEventModel, event_id)
            user = await session.get(UserModel, user_id)
            self.assertEqual(event.audio_status, "pending")
            self.assertIsNone(event.response_audio_path)
            self.assertEqual(user.credits, 2)
        self.assertFalse(path.exists())

    async def test_event_audio_api_distinguishes_pending_stale_deleted_and_disabled(self):
        from datetime import UTC, datetime
        from unittest.mock import patch

        from fastapi import HTTPException

        with patch.dict("os.environ", {"JWT_SECRET": self.settings.jwt_secret}):
            from app.api.sync import event_audio

        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "custom")
        user = SimpleNamespace(id=user_id)
        async with self.sessions() as session:
            response = await event_audio(event_id, user, session, 1)
            self.assertEqual(response.status_code, 202)
            with self.assertRaises(HTTPException) as error:
                await event_audio(event_id, user, session, 2)
            self.assertEqual(error.exception.status_code, 409)
            with self.assertRaises(HTTPException) as error:
                await event_audio(event_id, SimpleNamespace(id=uuid.uuid4()), session, 1)
            self.assertEqual(error.exception.status_code, 404)
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        async with self.sessions() as session:
            response = await event_audio(event_id, user, session, 1)
            self.assertEqual(response.headers["X-Event-Version"], "1")
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.version = 2
        async with self.sessions() as session:
            with self.assertRaises(HTTPException) as error:
                await event_audio(event_id, user, session, 2)
            self.assertEqual(error.exception.detail, "event_audio_version_stale")
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.speak_aloud = False
        async with self.sessions() as session:
            with self.assertRaises(HTTPException) as error:
                await event_audio(event_id, user, session, 2)
            self.assertEqual(error.exception.status_code, 422)
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.deleted_at = datetime.now(UTC)
        async with self.sessions() as session:
            with self.assertRaises(HTTPException) as error:
                await event_audio(event_id, user, session, 2)
            self.assertEqual(error.exception.status_code, 410)

    async def test_preparation_post_starts_pending_tts_without_waiting_for_maintenance(self):
        from unittest.mock import patch

        from fastapi import BackgroundTasks

        with patch.dict("os.environ", {"JWT_SECRET": self.settings.jwt_secret}):
            from app.api.sync import prepare_event_speech
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event", "reminder")
        self.speech._store.return_value = self.actual_audio(event_id)
        background = BackgroundTasks()
        with patch("app.api.sync.speech_service", return_value=self.speech):
            async with self.sessions() as session:
                result = await prepare_event_speech(
                    event_id, 1, SimpleNamespace(id=user_id), session, self.settings, background
                )
        self.assertEqual(result, {"audio_status": "pending"})
        self.assertEqual(len(background.tasks), 1)
        await background()
        self.kokoro.synthesize.assert_awaited_once()

    async def test_new_canonical_reminder_is_pending_without_nightly_planning(self):
        user_id, existing_id = await self.create("OPEN_SOURCE", 0, "event")
        event_id = uuid.uuid4()
        async with self.sessions() as session:
            existing = await session.get(DailyEventModel, existing_id)
            plan_id, event_date = existing.daily_plan_id, existing.event_date
        async with DailyPlanChanges(self.sessions, AsyncMock()).transaction(user_id) as session:
            session.add(
                DailyEventModel(
                    id=event_id,
                    user_id=user_id,
                    daily_plan_id=plan_id,
                    event_date=event_date,
                    title="Reminder from an order",
                    event_type="reminder",
                    speak_aloud=False,
                )
            )
        self.assertIn(("event", event_id), await self.speech.pending())
        self.kokoro.synthesize.assert_not_awaited()

    async def test_legacy_mismatched_binding_is_regenerated_not_relabelled(self):
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event")
        self.speech._store.return_value = self.actual_audio(event_id)
        await self.speech.generate("event", event_id)
        async with self.sessions() as session, session.begin():
            event = await session.get(DailyEventModel, event_id)
            event.version = 2
        async with self.sessions() as session:
            self.assertEqual(
                await self.speech.prepare_event(session, user_id, event_id, 2), ("pending", True)
            )
            event = await session.get(DailyEventModel, event_id)
            self.assertIsNone(event.response_audio_path)
        self.assertFalse((Path(self.directory.name) / f"{event_id}-2.ogg").exists())

    async def test_missing_ready_file_requeues_preparation(self):
        user_id, event_id = await self.create("OPEN_SOURCE", 0, "event")
        await self.speech.generate("event", event_id)
        async with self.sessions() as session:
            self.assertEqual(
                await self.speech.prepare_event(session, user_id, event_id, 1), ("pending", True)
            )

    async def test_shared_selection_applies_to_requests_and_events(self):
        for kind in ("request", "event"):
            for method, credits, use_openai in (
                ("OPEN_SOURCE", 3, False),
                ("OPENAI", 2, True),
                ("OPENAI", 0, False),
            ):
                self.openai.reset_mock()
                self.kokoro.reset_mock()
                user_id, owner_id = await self.create(method, credits, kind)
                await self.speech.generate(kind, owner_id)
                async with self.sessions() as session:
                    user = await session.get(UserModel, user_id)
                    row = await session.get(self.speech._model(kind), owner_id)
                self.assertEqual(row.audio_status, "ready")
                self.assertEqual(user.credits, credits - int(use_openai))
                self.assertEqual(user.tts_method, method)
                (self.openai if use_openai else self.kokoro).synthesize.assert_awaited_once()

    async def test_openai_failure_refunds_and_falls_back_for_event(self):
        user_id, owner_id = await self.create("OPENAI", 2, "event", "good_night_summary")
        self.openai.synthesize.side_effect = RuntimeError("OpenAI unavailable")
        await self.speech.generate("event", owner_id)
        async with self.sessions() as session:
            user = await session.get(UserModel, user_id)
            event = await session.get(DailyEventModel, owner_id)
        self.assertEqual(user.credits, 2)
        self.assertFalse(event.tts_credit_charged)
        self.assertEqual(event.audio_status, "ready")
        self.kokoro.synthesize.assert_awaited_once_with(
            text="Prepared morning text", request_id=owner_id
        )

    async def test_text_completes_while_speech_is_blocked(self):
        user_id, owner_id = uuid.uuid4(), uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=user_id, credits=3))
        blocked = asyncio.Event()

        async def wait_for_speech(kind, request_id):
            await blocked.wait()

        self.speech.generate = AsyncMock(side_effect=wait_for_speech)
        butler, push = AsyncMock(), AsyncMock()
        butler.handle.return_value = ButlerResult(response="Text now", user_message_text="Hello")
        service = ButlerRequestService(
            settings=self.settings,
            session_factory=self.sessions,
            butler=butler,
            push=push,
            speech=self.speech,
        )
        await service.accept_text(request_id=owner_id, user_id=user_id, message="Hello")
        await service.process(owner_id)
        result = await service.result(user_id, owner_id)
        self.assertEqual(result.status, "completed")
        self.assertEqual(result.response_text, "Text now")
        self.assertEqual(result.audio_status, "pending")
        self.assertEqual(result.warnings, [])
        blocked.set()
        await asyncio.sleep(0)


if __name__ == "__main__":
    unittest.main()
