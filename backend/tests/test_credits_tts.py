import asyncio
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import AsyncMock

from app.application.butler.contracts import ButlerResult, ButlerSpeech
from app.application.butler.requests import ButlerRequestService
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
