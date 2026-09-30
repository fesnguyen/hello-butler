import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.user_settings import (
    SavedContext,
    SavedContextInput,
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


@router.get("/saved-context", response_model=list[SavedContext])
async def list_saved_context(user: AuthenticatedUserDep, session: SessionDep) -> list[SavedContext]:
    return await UserSettingsService().list_context(session, user.id)


@router.put("/saved-context/{item_id}", response_model=SavedContext)
async def save_saved_context(
    item_id: uuid.UUID, update: SavedContextInput, user: AuthenticatedUserDep, session: SessionDep
) -> SavedContext:
    try:
        async with session.begin():
            return await UserSettingsService().save_context(session, user.id, item_id, update)
    except LookupError as exc:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc


@router.delete("/saved-context/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_saved_context(
    item_id: uuid.UUID,
    user: AuthenticatedUserDep,
    session: SessionDep,
    base_version: Annotated[int, Query(ge=1)],
) -> Response:
    try:
        async with session.begin():
            await UserSettingsService().delete_context(session, user.id, item_id, base_version)
    except LookupError as exc:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)
