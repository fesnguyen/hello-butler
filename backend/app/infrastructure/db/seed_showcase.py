"""Explicit, idempotent Showcase import; never runs during API startup."""

import argparse
import asyncio
import uuid
from pathlib import Path

from pydantic import AwareDatetime, BaseModel, Field, HttpUrl, TypeAdapter

from app.core.showcase_database import ShowcaseSessionLocal
from app.infrastructure.db.showcase import ShowcaseApplication, ShowcaseMedia, ShowcaseRelease


class SeedMedia(BaseModel):
    id: uuid.UUID
    url: HttpUrl
    display_order: int


class SeedRelease(BaseModel):
    id: uuid.UUID
    version: str = Field(min_length=1, max_length=100)
    download_url: HttpUrl
    published_at: AwareDatetime


class SeedApplication(BaseModel):
    id: uuid.UUID
    name: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1)
    github_url: HttpUrl | None = None
    media: list[SeedMedia] = Field(default_factory=list[SeedMedia])
    releases: list[SeedRelease] = Field(default_factory=list[SeedRelease])


async def seed(path: Path) -> None:
    applications = TypeAdapter(list[SeedApplication]).validate_json(path.read_text())
    async with ShowcaseSessionLocal.begin() as session:
        for item in applications:
            await session.merge(
                ShowcaseApplication(
                    id=item.id,
                    name=item.name,
                    description=item.description,
                    github_url=str(item.github_url) if item.github_url else None,
                )
            )
            await session.flush()
            for media in item.media:
                await session.merge(
                    ShowcaseMedia(
                        id=media.id,
                        application_id=item.id,
                        url=str(media.url),
                        display_order=media.display_order,
                    )
                )
            for release in item.releases:
                await session.merge(
                    ShowcaseRelease(
                        id=release.id,
                        application_id=item.id,
                        version=release.version,
                        download_url=str(release.download_url),
                        published_at=release.published_at,
                    )
                )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    asyncio.run(seed(args.manifest))


if __name__ == "__main__":
    main()
