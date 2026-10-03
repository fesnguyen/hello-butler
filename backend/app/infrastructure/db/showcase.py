import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, MetaData, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.infrastructure.db.base import convention


class ShowcaseBase(DeclarativeBase):
    metadata = MetaData(naming_convention=convention)


class ShowcaseApplication(ShowcaseBase):
    __tablename__ = "showcase_applications"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(200))
    description: Mapped[str] = mapped_column(Text)
    github_url: Mapped[str | None] = mapped_column(Text)


class ShowcaseMedia(ShowcaseBase):
    __tablename__ = "showcase_media"
    __table_args__ = (
        Index("ix_showcase_media_application_order", "application_id", "display_order"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    application_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("showcase_applications.id", ondelete="CASCADE")
    )
    url: Mapped[str] = mapped_column(Text)
    display_order: Mapped[int] = mapped_column(Integer)


class ShowcaseRelease(ShowcaseBase):
    __tablename__ = "showcase_releases"
    __table_args__ = (
        Index("ix_showcase_releases_application_date", "application_id", "published_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    application_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("showcase_applications.id", ondelete="CASCADE")
    )
    version: Mapped[str] = mapped_column(String(100))
    download_url: Mapped[str] = mapped_column(Text)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
