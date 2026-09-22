from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.user_settings import (
    UserSettingsService,
    UserSettingsState,
    UserSettingsUpdate,
)
from app.core.database import get_session

router = APIRouter(prefix="/api/user-settings", tags=["user-settings"])
SessionDep = Annotated[AsyncSession, Depends(get_session)]
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]


@router.get("", response_model=UserSettingsState)
async def get_user_settings(user: AuthenticatedUserDep, session: SessionDep) -> UserSettingsState:
    return await UserSettingsService().get(session, user.id)


@router.put("", response_model=UserSettingsState)
async def update_user_settings(
    update: UserSettingsUpdate,
    user: AuthenticatedUserDep,
    session: SessionDep,
) -> UserSettingsState:
    try:
        async with session.begin():
            return await UserSettingsService().update(session, user.id, update)
    except ValueError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
