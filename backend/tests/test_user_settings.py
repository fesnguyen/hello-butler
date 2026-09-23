import unittest
import uuid
from pathlib import Path
from tempfile import TemporaryDirectory

from app.application.user_settings import UserSettingsService, UserSettingsUpdate
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import UserContextEntryModel, UserModel
from pydantic import ValidationError
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"


class UserSettingsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'settings.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.user_id = uuid.uuid4()
        self.preference_id = uuid.uuid4()
        async with self.sessions() as session, session.begin():
            session.add(
                UserModel(
                    id=self.user_id,
                    email="user@example.test",
                    display_name="Old Name",
                    credits=7,
                    tts_method="OPEN_SOURCE",
                )
            )
            session.add_all(
                [
                    UserContextEntryModel(
                        id=self.preference_id,
                        user_id=self.user_id,
                        context_type="preference",
                        content="Prefers concise morning briefs",
                    ),
                    UserContextEntryModel(
                        user_id=self.user_id,
                        context_type="routine_daily",
                        content="Exercise after work",
                    ),
                ]
            )

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def test_read_only_credits_and_manageable_preference_filter(self):
        async with self.sessions() as session:
            state = await UserSettingsService().get(session, self.user_id)
        self.assertEqual(state.credits, 7)
        self.assertEqual([item.id for item in state.preferences], [self.preference_id])
        with self.assertRaises(ValidationError):
            UserSettingsUpdate.model_validate(
                {
                    "display_name": "New Name",
                    "tts_method": "OPENAI",
                    "known_preference_ids": [str(self.preference_id)],
                    "preference_ids": [str(self.preference_id)],
                    "credits": 999,
                }
            )

    async def test_save_updates_all_fields_and_soft_deletes_removed_preference(self):
        async with self.sessions() as session, session.begin():
            state = await UserSettingsService().update(
                session,
                self.user_id,
                UserSettingsUpdate(
                    display_name="New Name",
                    tts_method="OPENAI",
                    known_preference_ids=[self.preference_id],
                    preference_ids=[],
                ),
            )
        self.assertEqual(state.display_name, "New Name")
        self.assertEqual(state.tts_method, "OPENAI")
        self.assertEqual(state.credits, 7)
        self.assertEqual(state.preferences, [])
        async with self.sessions() as session:
            preference = await session.get(UserContextEntryModel, self.preference_id)
            user = await session.get(UserModel, self.user_id)
        self.assertIsNotNone(preference.deleted_at)
        self.assertEqual(user.credits, 7)


if __name__ == "__main__":
    unittest.main()
