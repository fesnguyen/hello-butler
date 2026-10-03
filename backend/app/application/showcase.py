import uuid
from datetime import UTC, datetime

from pydantic import BaseModel, ConfigDict, HttpUrl
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import aliased

from app.infrastructure.db.showcase import ShowcaseApplication, ShowcaseMedia, ShowcaseRelease


class Media(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    url: HttpUrl
    display_order: int


class Release(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    version: str
    download_url: HttpUrl
    published_at: datetime


class Application(BaseModel):
    id: uuid.UUID
    name: str
    description: str
    github_url: HttpUrl | None
    media: list[Media]
    latest_release: Release | None


class ShowcaseService:
    async def list_applications(self, session: AsyncSession) -> list[Application]:
        applications = list(
            await session.scalars(
                select(ShowcaseApplication).order_by(
                    ShowcaseApplication.name, ShowcaseApplication.id
                )
            )
        )
        if not applications:
            return []

        media_by_app: dict[uuid.UUID, list[Media]] = {}
        for item in await session.scalars(
            select(ShowcaseMedia).order_by(
                ShowcaseMedia.application_id, ShowcaseMedia.display_order, ShowcaseMedia.id
            )
        ):
            media_by_app.setdefault(item.application_id, []).append(Media.model_validate(item))

        # Rank in SQL rather than fetching every release or querying each application.
        ranked = (
            select(
                ShowcaseRelease,
                func.row_number()
                .over(
                    partition_by=ShowcaseRelease.application_id,
                    order_by=(ShowcaseRelease.published_at.desc(), ShowcaseRelease.id.desc()),
                )
                .label("rank"),
            )
            .where(ShowcaseRelease.published_at <= datetime.now(UTC))
            .subquery()
        )
        latest = aliased(ShowcaseRelease, ranked)
        releases = await session.scalars(select(latest).where(ranked.c.rank == 1))
        releases_by_app = {item.application_id: Release.model_validate(item) for item in releases}
        return [
            Application(
                id=item.id,
                name=item.name,
                description=item.description,
                github_url=HttpUrl(item.github_url) if item.github_url else None,
                media=media_by_app.get(item.id, []),
                latest_release=releases_by_app.get(item.id),
            )
            for item in applications
        ]
