from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Literal, cast

from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.infrastructure.db.models import UserContextEntryModel, UserModel

TTSMethod = Literal["OPEN_SOURCE", "OPENAI"]


class SavedPreference(BaseModel):
    id: uuid.UUID
    content: str


class UserSettingsState(BaseModel):
    display_name: str | None
    email: str | None
    credits: int
    tts_method: TTSMethod
    preferences: list[SavedPreference]


class UserSettingsUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    display_name: str | None = Field(default=None, max_length=120)
    tts_method: TTSMethod
    known_preference_ids: list[uuid.UUID] | None = None
    preference_ids: list[uuid.UUID] | None = None


class SavedContextInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=10000)
    is_preference: bool
    base_version: int = Field(ge=0)

    @field_validator("content")
    @classmethod
    def nonblank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Description is required")
        return value


class SavedContext(BaseModel):
    id: uuid.UUID
    content: str
    is_preference: bool
    version: int

    @classmethod
    def from_entry(cls, entry: UserContextEntryModel) -> SavedContext:
        return cls(
            id=entry.id,
            content=entry.content,
            is_preference=entry.context_type == "preference",
            version=entry.version,
        )


class UserSettingsService:
    async def list_context(self, session: AsyncSession, user_id: uuid.UUID) -> list[SavedContext]:
        entries = (
            await session.scalars(
                select(UserContextEntryModel)
                .where(
                    UserContextEntryModel.user_id == user_id,
                    UserContextEntryModel.context_type.in_(("note", "preference")),
                    UserContextEntryModel.is_actionable.is_(False),
                    UserContextEntryModel.deleted_at.is_(None),
                )
                .order_by(UserContextEntryModel.created_at.desc(), UserContextEntryModel.id)
            )
        ).all()
        return [SavedContext.from_entry(entry) for entry in entries]

    async def save_context(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        item_id: uuid.UUID,
        update: SavedContextInput,
    ) -> SavedContext:
        # Serialize creation retries on the existing owner row, including concurrent PUTs.
        if await session.get(UserModel, user_id, with_for_update=True) is None:
            raise LookupError("User not found")
        entry = await session.get(UserContextEntryModel, item_id, with_for_update=True)
        kind = "preference" if update.is_preference else "note"
        if entry is None:
            if update.base_version != 0:
                raise LookupError("Saved item not found")
            entry = UserContextEntryModel(
                id=item_id, user_id=user_id, context_type=kind, content=update.content
            )
            session.add(entry)
        else:
            self._check_manageable(entry, user_id)
            # A lost successful response can safely be retried without another write.
            if entry.content != update.content or entry.context_type != kind:
                if entry.version != update.base_version:
                    raise ValueError("Saved item changed; reopen it and try again")
                entry.content = update.content
                entry.context_type = kind
                entry.version += 1
        await session.flush()
        return SavedContext.from_entry(entry)

    async def delete_context(
        self, session: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID, base_version: int
    ) -> None:
        entry = await session.get(UserContextEntryModel, item_id, with_for_update=True)
        if entry is None or entry.user_id != user_id:
            raise LookupError("Saved item not found")
        if entry.deleted_at is not None:
            return
        self._check_manageable(entry, user_id)
        if entry.version != base_version:
            raise ValueError("Saved item changed; reopen it and try again")
        entry.deleted_at = datetime.now(UTC)
        entry.version += 1
        await session.flush()

    @staticmethod
    def _check_manageable(entry: UserContextEntryModel, user_id: uuid.UUID) -> None:
        if (
            entry.user_id != user_id
            or entry.deleted_at is not None
            or entry.context_type not in ("note", "preference")
            or entry.is_actionable
        ):
            raise LookupError("Saved item not found")

    async def get(self, session: AsyncSession, user_id: uuid.UUID) -> UserSettingsState:
        user = await session.get(UserModel, user_id)
        if user is None:
            raise LookupError("User not found")
        return await self._state(session, user)

    async def update(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        update: UserSettingsUpdate,
    ) -> UserSettingsState:
        user = await session.get(UserModel, user_id, with_for_update=True)
        if user is None:
            raise LookupError("User not found")
        preferences = []
        retained_ids: set[uuid.UUID] = set()
        # Older clients delete via the account form; new clients mutate individual items.
        if update.known_preference_ids is not None or update.preference_ids is not None:
            preferences = list(
                (
                    await session.scalars(
                        select(UserContextEntryModel)
                        .where(
                            UserContextEntryModel.user_id == user_id,
                            UserContextEntryModel.context_type == "preference",
                            UserContextEntryModel.deleted_at.is_(None),
                        )
                        .with_for_update()
                    )
                ).all()
            )
            current_ids = {item.id for item in preferences}
            known_ids = set(update.known_preference_ids or [])
            retained_ids = set(update.preference_ids or [])
            if (
                len(known_ids) != len(update.known_preference_ids or [])
                or len(retained_ids) != len(update.preference_ids or [])
                or current_ids != known_ids
                or not retained_ids <= known_ids
            ):
                raise ValueError("Preferences changed; refresh User Settings and try again")
        user.display_name = update.display_name.strip() or None if update.display_name else None
        user.tts_method = update.tts_method
        deleted_at = datetime.now(UTC)
        for preference in preferences:
            if preference.id not in retained_ids:
                preference.deleted_at = deleted_at
                preference.version += 1
        await session.flush()
        return await self._state(session, user)

    @staticmethod
    async def _state(session: AsyncSession, user: UserModel) -> UserSettingsState:
        preferences = list(
            (
                await session.scalars(
                    select(UserContextEntryModel)
                    .where(
                        UserContextEntryModel.user_id == user.id,
                        UserContextEntryModel.context_type == "preference",
                        UserContextEntryModel.deleted_at.is_(None),
                    )
                    .order_by(UserContextEntryModel.created_at.desc())
                )
            ).all()
        )
        return UserSettingsState(
            display_name=user.display_name,
            email=user.email,
            credits=user.credits,
            tts_method=cast(TTSMethod, user.tts_method),
            preferences=[SavedPreference(id=item.id, content=item.content) for item in preferences],
        )
