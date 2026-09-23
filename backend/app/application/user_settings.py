from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Literal, cast

from pydantic import BaseModel, ConfigDict, Field
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
    known_preference_ids: list[uuid.UUID]
    preference_ids: list[uuid.UUID]


class UserSettingsService:
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
        known_ids = set(update.known_preference_ids)
        retained_ids = set(update.preference_ids)
        if (
            len(known_ids) != len(update.known_preference_ids)
            or len(retained_ids) != len(update.preference_ids)
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
