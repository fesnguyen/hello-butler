import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import AsyncMock

from app.application.butler.contracts import ButlerSpeech
from app.application.butler.requests import ButlerRequestService
from app.application.credits import InsufficientCreditsError
from app.core.config import Settings
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import ButlerRequestModel, UserModel
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"


class CreditTtsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'credits.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.settings = Settings(
            jwt_secret="test-only-secret-32-characters-long",
            butler_reasoning_credit_cost=1,
            butler_openai_tts_credit_cost=1,
        )
        self.openai = AsyncMock()
        self.kokoro = AsyncMock()
        self.service = ButlerRequestService(
            settings=self.settings,
            session_factory=self.sessions,
            butler=AsyncMock(),
            push=AsyncMock(),
            voice_provider=self.openai,
            open_source_voice_provider=self.kokoro,
        )

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def _create(self, credits: int, method: str) -> tuple[uuid.UUID, uuid.UUID]:
        user_id, request_id = uuid.uuid4(), uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=user_id, credits=credits, tts_method=method))
            session.add(
                ButlerRequestModel(
                    id=request_id,
                    user_id=user_id,
                    input_source="text",
                    interaction_mode="talk",
                )
            )
        return user_id, request_id

    async def test_open_source_uses_kokoro_without_tts_charge(self):
        user_id, request_id = await self._create(3, "OPEN_SOURCE")
        provider, charged = await self.service._voice_provider_for(request_id, user_id)
        self.assertIs(provider, self.kokoro)
        self.assertFalse(charged)
        async with self.sessions() as session:
            self.assertEqual((await session.get(UserModel, user_id)).credits, 3)

    async def test_openai_uses_credit_then_falls_back_without_changing_preference(self):
        paid_user, paid_request = await self._create(1, "OPENAI")
        provider, charged = await self.service._voice_provider_for(paid_request, paid_user)
        self.assertIs(provider, self.openai)
        self.assertTrue(charged)
        async with self.sessions() as session:
            paid = await session.get(UserModel, paid_user)
            self.assertEqual(paid.credits, 0)
            self.assertEqual(paid.tts_method, "OPENAI")

        fallback_user, fallback_request = await self._create(0, "OPENAI")
        provider, charged = await self.service._voice_provider_for(fallback_request, fallback_user)
        self.assertIs(provider, self.kokoro)
        self.assertFalse(charged)
        async with self.sessions() as session:
            fallback = await session.get(UserModel, fallback_user)
            self.assertEqual(fallback.credits, 0)
            self.assertEqual(fallback.tts_method, "OPENAI")

    async def test_reasoning_charge_is_idempotent_and_never_negative(self):
        user_id, request_id = await self._create(1, "OPEN_SOURCE")
        await self.service._charge_reasoning(request_id, user_id)
        await self.service._charge_reasoning(request_id, user_id)
        async with self.sessions() as session:
            self.assertEqual((await session.get(UserModel, user_id)).credits, 0)

        empty_user, empty_request = await self._create(0, "OPEN_SOURCE")
        with self.assertRaises(InsufficientCreditsError):
            await self.service._charge_reasoning(empty_request, empty_user)
        async with self.sessions() as session:
            self.assertEqual((await session.get(UserModel, empty_user)).credits, 0)

    async def test_openai_failure_refunds_tts_credit_and_uses_kokoro(self):
        user_id, request_id = await self._create(2, "OPENAI")
        self.openai.synthesize.side_effect = RuntimeError("OpenAI unavailable")
        self.kokoro.synthesize.return_value = ButlerSpeech(
            audio=b"RIFF0000WAVEaudio", mime_type="audio/wav"
        )
        self.service._response_audio = AsyncMock(return_value=(Path("response.ogg"), "audio/ogg"))

        path, mime_type = await self.service._synthesize_response_audio(
            request_id, user_id, "Canonical response"
        )

        self.assertEqual(path, Path("response.ogg"))
        self.assertEqual(mime_type, "audio/ogg")
        self.kokoro.synthesize.assert_awaited_once_with(
            text="Canonical response", request_id=request_id
        )
        async with self.sessions() as session:
            user = await session.get(UserModel, user_id)
            request = await session.get(ButlerRequestModel, request_id)
        self.assertEqual(user.credits, 2)
        self.assertFalse(request.tts_credit_charged)


if __name__ == "__main__":
    unittest.main()
