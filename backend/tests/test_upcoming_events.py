import tempfile
import unittest
import uuid
from datetime import date, time, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.application.planning.service import DayPlanningService
from app.application.upcoming import (
    UpcomingEventMutation,
    UpcomingEventService,
    project_upcoming,
)
from app.core.config import Settings
from app.infrastructure.db.base import Base
from app.infrastructure.db.models import UserContextEntryModel, UserModel
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.ext.compiler import compiles


@compiles(JSONB, "sqlite")
def sqlite_jsonb(type_, compiler, **kwargs):
    return "JSON"


def context(**overrides):
    values = {
        "id": uuid.uuid4(),
        "context_type": "one_time",
        "content": "Dentist appointment",
        "starts_on": date(2026, 9, 22),
        "ends_on": date(2026, 9, 22),
        "is_actionable": True,
        "title": "Dentist",
        "start_time": time(15),
        "end_time": None,
        "recurrence": None,
        "recurrence_days": [],
        "occurrence_exceptions": [],
        "version": 1,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


class UpcomingProjectionTests(unittest.TestCase):
    def setUp(self):
        self.today = date(2026, 9, 19)

    def test_one_time_future_context(self):
        events = project_upcoming([context()], self.today)
        self.assertEqual(
            [(item.title, item.starts_on) for item in events], [("Dentist", date(2026, 9, 22))]
        )

    def test_recurring_future_context_projects_occurrences(self):
        row = context(
            context_type="temporary",
            title="Client meeting",
            starts_on=self.today,
            ends_on=self.today + timedelta(days=2),
            recurrence="daily",
        )
        events = project_upcoming([row], self.today)
        self.assertEqual(
            [item.starts_on for item in events],
            [self.today, self.today + timedelta(days=1), self.today + timedelta(days=2)],
        )
        self.assertTrue(all(item.recurring for item in events))

    def test_active_range_is_one_projection(self):
        row = context(
            context_type="temporary",
            title="Diet for a week",
            starts_on=self.today - timedelta(days=2),
            ends_on=self.today + timedelta(days=4),
            start_time=None,
        )
        events = project_upcoming([row], self.today)
        self.assertEqual(len(events), 1)
        self.assertEqual((events[0].starts_on, events[0].ends_on), (row.starts_on, row.ends_on))

    def test_expired_and_daily_routine_are_excluded(self):
        expired = context(
            starts_on=self.today - timedelta(days=4), ends_on=self.today - timedelta(days=1)
        )
        routine = context(context_type="routine_daily", starts_on=self.today, ends_on=None)
        self.assertEqual(project_upcoming([expired, routine], self.today), [])

    def test_projection_does_not_create_daily_events(self):
        events = project_upcoming([context()], self.today)
        self.assertEqual(len(events), 1)
        self.assertFalse(hasattr(events[0], "daily_plan_id"))


class UpcomingMutationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'test.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.user_id = uuid.uuid4()
        self.context_id = uuid.uuid4()
        self.today = date(2026, 9, 19)
        async with self.sessions() as session, session.begin():
            session.add(UserModel(id=self.user_id))
            session.add(
                UserContextEntryModel(
                    id=self.context_id,
                    user_id=self.user_id,
                    context_type="temporary",
                    content="Meet the client every day this week",
                    title="Client meeting",
                    starts_on=self.today,
                    ends_on=self.today + timedelta(days=4),
                    start_time=time(15),
                    is_actionable=True,
                    recurrence="daily",
                    recurrence_days=[],
                    occurrence_exceptions=[],
                    version=1,
                )
            )

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def test_occurrence_skip_preserves_recurring_rule(self):
        tomorrow = self.today + timedelta(days=1)
        async with self.sessions() as session, session.begin():
            result = await UpcomingEventService().mutate(
                session,
                self.user_id,
                self.context_id,
                UpcomingEventMutation(
                    action="skip", scope="occurrence", base_version=1, occurrence_date=tomorrow
                ),
                self.today,
            )
        self.assertNotIn(tomorrow, [item.starts_on for item in result.upcoming_events])
        async with self.sessions() as session:
            row = await session.get(UserContextEntryModel, self.context_id)
            self.assertIsNone(row.deleted_at)
            self.assertEqual(row.recurrence, "daily")
            self.assertEqual(len(row.occurrence_exceptions), 1)

    async def test_upcoming_event_is_available_to_planning_without_becoming_daily_event(self):
        service = DayPlanningService(
            Settings(jwt_secret="test-only-secret-32-characters-long"),
            self.sessions,
            AsyncMock(),
            AsyncMock(),
        )
        planning_input = await service._load_input(self.user_id, self.today)
        self.assertEqual(planning_input.upcoming_events[0].title, "Client meeting")
        self.assertEqual(planning_input.known_events, [])

    async def test_rule_update_then_remove_changes_source_context(self):
        async with self.sessions() as session, session.begin():
            updated = await UpcomingEventService().mutate(
                session,
                self.user_id,
                self.context_id,
                UpcomingEventMutation(
                    action="modify", scope="rule", base_version=1, title="Partner meeting"
                ),
                self.today,
            )
        self.assertTrue(all(item.title == "Partner meeting" for item in updated.upcoming_events))
        async with self.sessions() as session, session.begin():
            removed = await UpcomingEventService().mutate(
                session,
                self.user_id,
                self.context_id,
                UpcomingEventMutation(action="remove", scope="rule", base_version=2),
                self.today,
            )
        self.assertEqual(removed.upcoming_events, [])
