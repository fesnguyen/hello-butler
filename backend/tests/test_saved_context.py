import uuid
from datetime import date

from app.application.butler.actions.user_context import UserContextActions
from app.application.butler.context import ButlerContextLoader
from app.application.butler.contracts import ButlerDecision, ButlerInteractionProposal
from app.application.planning.service import DayPlanningService
from app.application.user_settings import SavedContextInput, UserSettingsService, UserSettingsUpdate
from app.core.config import Settings
from app.infrastructure.db.models import UserContextEntryModel
from pydantic import ValidationError
from sqlalchemy import func, select
from test_user_settings import UserSettingsTests


class SavedContextTests(UserSettingsTests):
    async def test_conversation_and_settings_share_identity_and_later_context(self):
        decision = ButlerDecision(
            intent="command",
            requested_action="remember_user_context",
            context_type="preference",
            context_content="I love the beach on days off",
        )
        state = {
            "user_id": self.user_id,
            "decision": decision,
            "proposal": ButlerInteractionProposal(
                thought="Save preference",
                user_message_text="I love the beach",
                decision=decision,
                response_text="Remembered.",
            ),
        }
        result = await UserContextActions(self.sessions).remember(state)
        item_id = result["result"].changed_entities[0].id
        async with self.sessions() as session, session.begin():
            service = UserSettingsService()
            saved = {item.id: item for item in await service.list_context(session, self.user_id)}
            self.assertIn(item_id, saved)
            await service.save_context(
                session,
                self.user_id,
                item_id,
                SavedContextInput(content="Prefer mountains", is_preference=True, base_version=1),
            )
        loader = ButlerContextLoader(
            Settings(jwt_secret="test-secret-for-saved-context-32-bytes"), self.sessions
        )
        context = await loader.load(self.user_id, date.today())
        self.assertEqual(
            next(x.content for x in context.user_context if x.id == item_id), "Prefer mountains"
        )
        decision = ButlerDecision(
            intent="command",
            requested_action="update_user_context",
            target_context_id=item_id,
            context_content="Prefer quiet beaches",
        )
        state["decision"] = decision
        await UserContextActions(self.sessions).update(state)
        async with self.sessions() as session:
            saved = {item.id: item for item in await service.list_context(session, self.user_id)}
            self.assertEqual(saved[item_id].content, "Prefer quiet beaches")

    async def test_direct_preference_creation_and_deletion_use_authoritative_context(self):
        service = UserSettingsService()
        item_id = uuid.uuid4()
        async with self.sessions() as session, session.begin():
            item = await service.save_context(
                session,
                self.user_id,
                item_id,
                SavedContextInput(
                    content="Prefer short briefs", is_preference=True, base_version=0
                ),
            )
            row = await session.get(UserContextEntryModel, item_id)
            self.assertEqual(row.context_type, "preference")
            self.assertEqual(row.content, item.content)
        async with self.sessions() as session, session.begin():
            await service.delete_context(session, self.user_id, item_id, item.version)
        loader = ButlerContextLoader(
            Settings(jwt_secret="test-secret-for-saved-context-32-bytes"), self.sessions
        )
        self.assertNotIn(
            item_id,
            [row.id for row in (await loader.load(self.user_id, date.today())).user_context],
        )

    async def test_note_switch_edit_delete_and_retry(self):
        service = UserSettingsService()
        item_id = uuid.uuid4()
        update = SavedContextInput(
            content="  Ask John about a laptop  ", is_preference=False, base_version=0
        )
        async with self.sessions() as session, session.begin():
            note = await service.save_context(session, self.user_id, item_id, update)
            retry = await service.save_context(session, self.user_id, item_id, update)
            self.assertEqual(note, retry)
            self.assertEqual(
                await session.scalar(
                    select(func.count())
                    .select_from(UserContextEntryModel)
                    .where(UserContextEntryModel.id == item_id)
                ),
                1,
            )
            self.assertEqual(
                (await session.get(UserContextEntryModel, item_id)).context_type, "note"
            )
        loader = ButlerContextLoader(
            Settings(jwt_secret="test-secret-for-saved-context-32-bytes"), self.sessions
        )
        self.assertNotIn(
            item_id, [x.id for x in (await loader.load(self.user_id, date.today())).user_context]
        )
        # Verify the planning input boundary too, without invoking AI or speech.
        planner = object.__new__(DayPlanningService)
        planner._session_factory = self.sessions
        planner._settings = Settings(jwt_secret="test-secret-for-saved-context-32-bytes")
        plan = await planner._load_input(self.user_id, date.today())
        self.assertNotIn(note.content, [x.content for x in plan.user_context])
        async with self.sessions() as session, session.begin():
            preference = await service.save_context(
                session,
                self.user_id,
                item_id,
                SavedContextInput(
                    content="Prefer lightweight laptops",
                    is_preference=True,
                    base_version=note.version,
                ),
            )
            self.assertTrue(preference.is_preference)
        self.assertIn(
            item_id, [x.id for x in (await loader.load(self.user_id, date.today())).user_context]
        )
        async with self.sessions() as session, session.begin():
            note = await service.save_context(
                session,
                self.user_id,
                item_id,
                SavedContextInput(
                    content="Laptop serial number",
                    is_preference=False,
                    base_version=preference.version,
                ),
            )
            self.assertFalse(note.is_preference)
            await service.delete_context(session, self.user_id, item_id, note.version)
            await service.delete_context(session, self.user_id, item_id, note.version)
            self.assertNotIn(
                item_id, [x.id for x in await service.list_context(session, self.user_id)]
            )
            with self.assertRaises(LookupError):
                await service.save_context(session, self.user_id, item_id, update)

    async def test_projection_excludes_other_categories_and_other_users(self):
        async with self.sessions() as session, session.begin():
            for kind in [
                "reference",
                "routine_daily",
                "routine_weekly",
                "temporary",
                "one_time",
                "internal",
                "workday",
                "dayoff",
            ]:
                session.add(
                    UserContextEntryModel(user_id=self.user_id, context_type=kind, content="Hidden")
                )
            session.add(
                UserContextEntryModel(
                    user_id=self.user_id,
                    context_type="preference",
                    content="Actionable",
                    is_actionable=True,
                )
            )
            session.add(
                UserContextEntryModel(
                    user_id=uuid.uuid4(), context_type="preference", content="Other user"
                )
            )
        async with self.sessions() as session:
            items = await UserSettingsService().list_context(session, self.user_id)
            self.assertEqual([x.id for x in items], [self.preference_id])

    async def test_stale_edits_and_cross_user_mutations_rejected(self):
        service = UserSettingsService()
        async with self.sessions() as session, session.begin():
            with self.assertRaises(ValueError):
                await service.save_context(
                    session,
                    self.user_id,
                    self.preference_id,
                    SavedContextInput(content="Changed", is_preference=True, base_version=0),
                )
            with self.assertRaises(LookupError):
                await service.delete_context(session, uuid.uuid4(), self.preference_id, 1)
        with self.assertRaises(ValidationError):
            SavedContextInput(content="   ", is_preference=False, base_version=0)

    async def test_account_save_does_not_delete_conversation_preferences(self):
        async with self.sessions() as session, session.begin():
            await UserSettingsService().update(
                session, self.user_id, UserSettingsUpdate(display_name="New", tts_method="OPENAI")
            )
            items = await UserSettingsService().list_context(session, self.user_id)
            self.assertEqual([x.id for x in items], [self.preference_id])
