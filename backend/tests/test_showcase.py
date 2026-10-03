import unittest
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from app.api.showcase import router
from app.application.showcase import ShowcaseService
from app.core.config import Settings
from app.core.database import get_session
from app.core.showcase_database import get_showcase_session
from app.infrastructure.db.base import Base
from app.infrastructure.db.seed_showcase import seed
from app.infrastructure.db.showcase import (
    ShowcaseApplication,
    ShowcaseBase,
    ShowcaseMedia,
    ShowcaseRelease,
)
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError
from sqlalchemy import event, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine


class ShowcaseTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = TemporaryDirectory()
        self.engine = create_async_engine(
            f"sqlite+aiosqlite:///{Path(self.directory.name) / 'showcase.db'}"
        )
        async with self.engine.begin() as connection:
            await connection.run_sync(ShowcaseBase.metadata.create_all)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.queries = []
        event.listen(self.engine.sync_engine, "before_cursor_execute", self.record_query)

    def record_query(self, conn, cursor, statement, parameters, context, executemany):
        self.queries.append(statement)

    async def asyncTearDown(self):
        await self.engine.dispose()
        self.directory.cleanup()

    async def test_empty_collection(self):
        async with self.sessions() as session:
            self.assertEqual(await ShowcaseService().list_applications(session), [])
        self.assertEqual(len(self.queries), 1)

    async def test_ordered_media_latest_published_release_and_empty_application(self):
        app_id, empty_id = uuid.uuid4(), uuid.uuid4()
        now = datetime.now(UTC)
        async with self.sessions.begin() as session:
            session.add_all(
                [
                    ShowcaseApplication(
                        id=app_id,
                        name="Butler",
                        description="AI assistant",
                        github_url="https://github.com/fesnguyen/hello-butler",
                    ),
                    ShowcaseApplication(id=empty_id, name="Empty", description="Coming soon"),
                ]
            )
            await session.flush()
            session.add_all(
                [
                    ShowcaseMedia(
                        application_id=app_id, url="https://example.com/second.png", display_order=2
                    ),
                    ShowcaseMedia(
                        application_id=app_id, url="https://example.com/first.png", display_order=0
                    ),
                    ShowcaseRelease(
                        application_id=app_id,
                        version="old",
                        download_url="https://example.com/old.apk",
                        published_at=now - timedelta(days=2),
                    ),
                    ShowcaseRelease(
                        application_id=app_id,
                        version="latest",
                        download_url="https://example.com/latest.apk",
                        published_at=now - timedelta(days=1),
                    ),
                    ShowcaseRelease(
                        application_id=app_id,
                        version="future",
                        download_url="https://example.com/future.apk",
                        published_at=now + timedelta(days=1),
                    ),
                ]
            )
        self.queries.clear()
        async with self.sessions() as session:
            result = await ShowcaseService().list_applications(session)
        self.assertEqual(len(self.queries), 3)
        self.assertEqual([app.id for app in result], [app_id, empty_id])
        self.assertEqual([media.display_order for media in result[0].media], [0, 2])
        self.assertEqual(result[0].latest_release.version, "latest")
        self.assertEqual(result[1].media, [])
        self.assertIsNone(result[1].latest_release)
        self.assertIsNone(result[1].github_url)

    async def test_public_route_uses_only_showcase_session(self):
        async with self.sessions.begin() as session:
            session.add(ShowcaseApplication(name="Public app", description="Public data"))

        async def showcase_session():
            async with self.sessions() as session:
                yield session

        async def forbidden_butler_session():
            raise AssertionError("Showcase must never request the Butler session")

        app = FastAPI()
        app.include_router(router)
        app.dependency_overrides[get_showcase_session] = showcase_session
        app.dependency_overrides[get_session] = forbidden_butler_session
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.get("/api/showcase/applications")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()[0]["name"], "Public app")
            self.assertEqual(response.json()[0]["media"], [])
            self.assertIsNone(response.json()[0]["latest_release"])
            self.assertEqual((await client.post("/api/showcase/applications")).status_code, 405)
        self.assertIsNot(ShowcaseBase.metadata, Base.metadata)
        self.assertEqual(
            set(ShowcaseBase.metadata.tables),
            {
                "showcase_applications",
                "showcase_media",
                "showcase_releases",
            },
        )
        self.assertFalse(set(ShowcaseBase.metadata.tables) & set(Base.metadata.tables))

    async def test_explicit_bootstrap_is_idempotent(self):
        manifest = Path(__file__).parents[1] / "showcase.seed.example.json"
        with patch("app.infrastructure.db.seed_showcase.ShowcaseSessionLocal", self.sessions):
            await seed(manifest)
            await seed(manifest)
        async with self.sessions() as session:
            self.assertEqual(
                await session.scalar(select(func.count()).select_from(ShowcaseApplication)), 1
            )
            applications = await ShowcaseService().list_applications(session)
        self.assertEqual(applications[0].name, "Hello Butler")
        self.assertEqual(applications[0].media, [])
        self.assertIsNone(applications[0].latest_release)

    def test_configuration_rejects_shared_database(self):
        with self.assertRaises(ValidationError):
            Settings(
                jwt_secret="showcase-test-secret-at-least-32-bytes",
                database_url="postgresql+asyncpg://localhost/shared",
                showcase_database_url="postgresql+asyncpg://localhost/shared",
            )
