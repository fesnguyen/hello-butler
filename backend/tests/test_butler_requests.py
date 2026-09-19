import asyncio
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.application.butler.contracts import (
    ButlerAIUnavailableError,
    ButlerInteractionProposal,
    ButlerSpeech,
)
from app.application.butler.requests import ButlerRequestService
from app.application.butler.service import ButlerService
from app.application.push.changes import DailyPlanChanges
from app.application.push.service import PushService
from app.core.config import Settings
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import (
    ButlerRequestModel,
    ConversationMessageModel,
    DailyEventModel,
    UserModel,
)
from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"


class ButlerRequestTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'test.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.user_id = uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=self.user_id))

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def test_duplicate_processing_produces_one_action_and_canonical_pair(self):
        settings = Settings(
            jwt_secret="test-only-secret-32-characters-long",
            butler_audio_root=str(Path(self.directory.name) / "audio"),
        )
        push_provider = AsyncMock()

        async def send_after_commit(tokens, data):
            if data["type"] == "butler_request_completed":
                async with self.sessions() as session:
                    persisted = await session.get(ButlerRequestModel, request_id)
                    self.assertEqual(persisted.status, "completed")
            return set()

        push_provider.send_data.side_effect = send_after_commit
        push = PushService(self.sessions, push_provider)
        await push.register(self.user_id, "phone-token")
        ai = AsyncMock()
        ai.interact.return_value = ButlerInteractionProposal(
            thought="The user asked to add exercise and the response confirms it.",
            user_message_text="  Add exercise.  ",
            response_text="Done. I added Exercise today.",
            decision={
                "intent": "command",
                "requested_action": "create_daily_event",
                "title": "Exercise",
            },
        )
        voice = AsyncMock()
        voice.synthesize.return_value = ButlerSpeech(
            audio=b"RIFF0000WAVEaudio", mime_type="audio/wav"
        )
        butler = ButlerService(
            settings=settings,
            session_factory=self.sessions,
            ai_provider=ai,
            changes=DailyPlanChanges(self.sessions, push),
        )
        service = ButlerRequestService(
            settings=settings,
            session_factory=self.sessions,
            butler=butler,
            push=push,
            voice_provider=voice,
        )
        request_id = uuid.uuid4()
        exact = "  Add exercise.  "
        await service.accept_text(request_id=request_id, user_id=self.user_id, message=exact)
        with patch(
            "app.application.butler.history.ButlerHistoryWriter.save",
            side_effect=RuntimeError("crash after action commit"),
        ):
            await service.process(request_id)
        async with self.sessions() as session:
            failed = await session.get(ButlerRequestModel, request_id)
            self.assertEqual(failed.status, "failed")

        with patch.object(
            service,
            "_encode_response_ogg",
            AsyncMock(return_value=b"OggS" + b"0" * 24 + b"OpusHead"),
        ):
            await asyncio.gather(service.process(request_id), service.process(request_id))

        async with self.sessions() as session:
            request = await session.get(ButlerRequestModel, request_id)
            event_count = await session.scalar(select(func.count()).select_from(DailyEventModel))
            messages = list((await session.scalars(select(ConversationMessageModel))).all())
        self.assertEqual(request.status, "completed")
        self.assertEqual(request.user_message_text, exact)
        self.assertEqual(request.response_text, "Done. I added Exercise today.")
        self.assertEqual(request.response_audio_mime_type, "audio/ogg")
        self.assertTrue(request.response_audio_path.endswith(".ogg"))
        self.assertTrue(Path(request.response_audio_path).is_file())
        self.assertEqual(event_count, 1)
        self.assertEqual(len(messages), 2)
        self.assertEqual({item.role for item in messages}, {"user", "butler"})
        self.assertEqual(next(item.content for item in messages if item.role == "user"), exact)
        voice.synthesize.assert_awaited_once_with(
            text="Done. I added Exercise today.", request_id=request_id
        )
        sent_types = [call.args[1]["type"] for call in push_provider.send_data.await_args_list]
        self.assertIn("butler_request_handling", sent_types)
        self.assertIn("butler_request_completed", sent_types)

    async def test_response_audio_failure_keeps_completed_text_result(self):
        settings = Settings(
            jwt_secret="test-only-secret-32-characters-long",
            butler_audio_root=str(Path(self.directory.name) / "audio"),
        )
        push = PushService(self.sessions, AsyncMock())
        ai = AsyncMock()
        ai.interact.return_value = ButlerInteractionProposal(
            thought="The user asked for today's plan and the response answers it.",
            user_message_text="What is planned?",
            response_text="You have no events today.",
            decision={"intent": "query", "requested_action": "none"},
        )
        voice = AsyncMock()
        voice.synthesize.return_value = ButlerSpeech(
            audio=b"RIFF0000WAVEaudio", mime_type="audio/wav"
        )
        service = ButlerRequestService(
            settings=settings,
            session_factory=self.sessions,
            butler=ButlerService(
                settings=settings,
                session_factory=self.sessions,
                ai_provider=ai,
                changes=DailyPlanChanges(self.sessions, push),
            ),
            push=push,
            voice_provider=voice,
        )
        request_id = uuid.uuid4()
        await service.accept_text(
            request_id=request_id, user_id=self.user_id, message="What is planned?"
        )
        with patch.object(
            service,
            "_encode_response_ogg",
            AsyncMock(side_effect=ValueError("encoder failed")),
        ):
            await service.process(request_id)

        result = await service.result(self.user_id, request_id)
        self.assertEqual(result.status, "completed")
        self.assertEqual(result.response_text, "You have no events today.")
        self.assertIsNone(result.response_audio_url)
        self.assertTrue(result.warnings)

    async def test_tts_failure_and_disabled_voice_complete_text_only(self):
        for enabled in (True, False):
            with self.subTest(enabled=enabled):
                settings = Settings(
                    jwt_secret="test-only-secret-32-characters-long",
                    butler_audio_root=str(Path(self.directory.name) / "audio"),
                    butler_voice_enabled=enabled,
                )
                push = PushService(self.sessions, AsyncMock())
                ai = AsyncMock()
                ai.interact.return_value = ButlerInteractionProposal(
                    thought="The user greeted the Butler.",
                    user_message_text="Hello",
                    response_text="Hello. How can I help?",
                    decision={"intent": "query", "requested_action": "none"},
                )
                voice = AsyncMock()
                voice.synthesize.side_effect = RuntimeError("TTS unavailable")
                service = ButlerRequestService(
                    settings=settings,
                    session_factory=self.sessions,
                    butler=ButlerService(
                        settings=settings,
                        session_factory=self.sessions,
                        ai_provider=ai,
                        changes=DailyPlanChanges(self.sessions, push),
                    ),
                    push=push,
                    voice_provider=voice,
                )
                request_id = uuid.uuid4()
                await service.accept_text(
                    request_id=request_id, user_id=self.user_id, message="Hello"
                )
                await service.process(request_id)

                result = await service.result(self.user_id, request_id)
                self.assertEqual(result.status, "completed")
                self.assertEqual(result.response_text, "Hello. How can I help?")
                self.assertIsNone(result.response_audio_url)
                if enabled:
                    voice.synthesize.assert_awaited_once()
                    self.assertTrue(result.warnings)
                else:
                    voice.synthesize.assert_not_awaited()
                    self.assertFalse(result.warnings)

    async def test_audio_understanding_failure_fails_and_retains_input_for_recovery(self):
        settings = Settings(
            jwt_secret="test-only-secret-32-characters-long",
            butler_audio_root=str(Path(self.directory.name) / "audio"),
        )
        push = PushService(self.sessions, AsyncMock())
        ai = AsyncMock()
        ai.interact.side_effect = ButlerAIUnavailableError("Uploaded audio could not be decoded")
        voice = AsyncMock()
        service = ButlerRequestService(
            settings=settings,
            session_factory=self.sessions,
            butler=ButlerService(
                settings=settings,
                session_factory=self.sessions,
                ai_provider=ai,
                changes=DailyPlanChanges(self.sessions, push),
            ),
            push=push,
            voice_provider=voice,
        )
        input_path = Path(self.directory.name) / "pending.ogg"
        input_path.write_bytes(b"OggS" + b"0" * 24 + b"OpusHead")
        request_id = uuid.uuid4()
        await service.accept_audio(
            request_id=request_id,
            user_id=self.user_id,
            interaction_mode="order",
            path=input_path,
            mime_type="audio/ogg",
        )

        await service.process(request_id)

        result = await service.result(self.user_id, request_id)
        self.assertEqual(result.status, "failed")
        self.assertTrue(input_path.is_file())
        async with self.sessions() as session:
            request = await session.get(ButlerRequestModel, request_id)
        self.assertEqual(request.input_audio_path, str(input_path))
        self.assertIsNotNone(request.input_audio_delete_after)
        voice.synthesize.assert_not_awaited()

    def test_response_retention_defaults_to_one_day_and_is_configurable(self):
        default = Settings(jwt_secret="test-only-secret-32-characters-long")
        override = Settings(
            jwt_secret="test-only-secret-32-characters-long",
            butler_response_audio_retention_days=3,
        )
        self.assertEqual(default.butler_response_audio_retention_days, 1)
        self.assertEqual(override.butler_response_audio_retention_days, 3)


if __name__ == "__main__":
    unittest.main()
