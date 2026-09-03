from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.application.auth import (
    AuthConflict,
    AuthenticatedUser,
    AuthInvalidCredentials,
    AuthService,
    AuthTokens,
    AuthUnavailable,
)
from app.core.config import Settings, get_settings
from app.core.database import get_session
from app.core.security import decode_access_token
from app.infrastructure.auth import GoogleIdentityVerifier
from app.infrastructure.db.models import UserModel

router = APIRouter(prefix="/api/auth", tags=["auth"])
SessionDep = Annotated[AsyncSession, Depends(get_session)]
SettingsDep = Annotated[Settings, Depends(get_settings)]
bearer_scheme = HTTPBearer(auto_error=False)


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1)


class GoogleAuthRequest(BaseModel):
    id_token: str = Field(min_length=1)


class RefreshRequest(BaseModel):
    refresh_token: str = Field(min_length=1)


class LogoutRequest(BaseModel):
    refresh_token: str = Field(min_length=1)


def _service(settings: Settings) -> AuthService:
    return AuthService(settings, GoogleIdentityVerifier())


async def get_authenticated_user(
    session: SessionDep,
    settings: SettingsDep,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
) -> AuthenticatedUser:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    try:
        user_id = decode_access_token(settings, credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated") from exc

    user = await session.get(UserModel, user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    authenticated_user = AuthenticatedUser(
        id=user.id, email=user.email, display_name=user.display_name
    )
    await session.rollback()  # Release transaction before downstream OpenAI work.
    return authenticated_user


@router.post("/register")
async def register(
    request: RegisterRequest, session: SessionDep, settings: SettingsDep
) -> AuthTokens:
    try:
        return await _service(settings).register(session, str(request.email), request.password)
    except AuthInvalidCredentials as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
    except AuthConflict as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc


@router.post("/login")
async def login(request: LoginRequest, session: SessionDep, settings: SettingsDep) -> AuthTokens:
    try:
        return await _service(settings).login(session, str(request.email), request.password)
    except AuthInvalidCredentials as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc


@router.post("/google")
async def google_auth(
    request: GoogleAuthRequest, session: SessionDep, settings: SettingsDep
) -> AuthTokens:
    try:
        return await _service(settings).google(session, request.id_token)
    except AuthUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    except AuthInvalidCredentials as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc
    except AuthConflict as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc


@router.post("/refresh")
async def refresh(
    request: RefreshRequest, session: SessionDep, settings: SettingsDep
) -> AuthTokens:
    try:
        return await _service(settings).refresh(session, request.refresh_token)
    except AuthInvalidCredentials as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc


@router.post("/logout")
async def logout(
    request: LogoutRequest, session: SessionDep, settings: SettingsDep
) -> dict[str, str]:
    await _service(settings).logout(session, request.refresh_token)
    return {"status": "ok"}
