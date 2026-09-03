import asyncio
import uuid
from collections.abc import Mapping
from datetime import datetime, timedelta
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from google.auth.transport import requests
from google.oauth2 import id_token
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import Select, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.database import get_session
from app.core.security import (
    create_access_token,
    decode_access_token,
    hash_password,
    hash_refresh_token,
    new_refresh_token,
    normalize_email,
    now_utc,
    verify_password,
)
from app.infrastructure.db.models import AuthIdentityModel, RefreshSessionModel, UserModel

router = APIRouter(prefix="/api/auth", tags=["auth"])
SessionDep = Annotated[AsyncSession, Depends(get_session)]
SettingsDep = Annotated[Settings, Depends(get_settings)]

bearer_scheme = HTTPBearer(auto_error=False)


class AuthTokens(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


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


class AuthenticatedUser(BaseModel):
    id: uuid.UUID
    email: str | None
    display_name: str | None


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
    email = normalize_email(str(request.email))
    if len(request.password) < settings.password_min_length:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Password does not meet requirements")

    if await _scalar(session, _auth_identity_by_provider("password", email)):
        raise HTTPException(status.HTTP_409_CONFLICT, "Email is already registered")

    user_id = uuid.uuid4()
    user = UserModel(id=user_id, email=email)
    identity = AuthIdentityModel(
        user_id=user_id,
        provider="password",
        provider_subject=email,
        email=email,
        email_verified=False,
        password_hash=hash_password(request.password),
    )
    session.add(user)
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "Email is already registered") from exc
    session.add(identity)
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "Email is already registered") from exc

    tokens = _build_session(settings, user_id)
    session.add(tokens[0])
    await session.commit()
    return tokens[1]


@router.post("/login")
async def login(request: LoginRequest, session: SessionDep, settings: SettingsDep) -> AuthTokens:
    failure = HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid email or password")
    email = normalize_email(str(request.email))
    identity = await _scalar(session, _auth_identity_by_provider("password", email))
    if identity is None or identity.password_hash is None:
        raise failure
    if not verify_password(identity.password_hash, request.password):
        raise failure

    refresh_session, auth_tokens = _build_session(settings, identity.user_id)
    session.add(refresh_session)
    await session.commit()
    return auth_tokens


@router.post("/google")
async def google_auth(
    request: GoogleAuthRequest, session: SessionDep, settings: SettingsDep
) -> AuthTokens:
    if not settings.google_oauth_client_id:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Google authentication is not configured"
        )

    claims = await _verify_google_id_token(request.id_token, settings.google_oauth_client_id)
    provider_subject = str(claims["sub"])
    email = normalize_email(str(claims["email"])) if claims.get("email") else None
    email_verified = claims.get("email_verified") is True
    if email is None or not email_verified:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid Google token")

    identity = await _scalar(session, _auth_identity_by_provider("google", provider_subject))

    if identity is None:
        if await _scalar(session, select(UserModel).where(UserModel.email == email)):
            raise HTTPException(status.HTTP_409_CONFLICT, "Google account is not linked")
        user_id = uuid.uuid4()
        name = claims.get("name")
        user = UserModel(
            id=user_id,
            email=email,
            display_name=name if isinstance(name, str) else None,
        )
        identity = AuthIdentityModel(
            user_id=user_id,
            provider="google",
            provider_subject=provider_subject,
            email=email,
            email_verified=email_verified,
        )
        session.add(user)
        try:
            await session.flush()
        except IntegrityError as exc:
            await session.rollback()
            raise HTTPException(status.HTTP_409_CONFLICT, "Google account is not linked") from exc
        session.add(identity)
        try:
            await session.flush()
        except IntegrityError as exc:
            await session.rollback()
            raise HTTPException(status.HTTP_409_CONFLICT, "Google account is not linked") from exc

    refresh_session, auth_tokens = _build_session(settings, identity.user_id)
    session.add(refresh_session)
    await session.commit()
    return auth_tokens


@router.post("/refresh")
async def refresh(
    request: RefreshRequest, session: SessionDep, settings: SettingsDep
) -> AuthTokens:
    token_hash = hash_refresh_token(request.refresh_token)
    refresh_session = await _scalar(
        session,
        select(RefreshSessionModel)
        .where(RefreshSessionModel.token_hash == token_hash)
        .with_for_update(),
    )
    if refresh_session is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid refresh token")

    now = now_utc()
    if (
        refresh_session.revoked_at is not None
        or refresh_session.consumed_at is not None
        or refresh_session.expires_at <= now
    ):
        await _revoke_refresh_session(session, refresh_session.session_id, reused=True)
        await session.commit()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid refresh token")

    replacement, auth_tokens = _build_session(
        settings,
        refresh_session.user_id,
        session_id=refresh_session.session_id,
        expires_at=refresh_session.expires_at,
    )
    refresh_session.consumed_at = now
    refresh_session.last_used_at = now
    session.add(replacement)
    await session.flush()
    refresh_session.replaced_by_id = replacement.id
    await session.commit()
    return auth_tokens


@router.post("/logout")
async def logout(request: LogoutRequest, session: SessionDep) -> dict[str, str]:
    token_hash = hash_refresh_token(request.refresh_token)
    refresh_session = await _scalar(
        session,
        select(RefreshSessionModel)
        .where(RefreshSessionModel.token_hash == token_hash)
        .with_for_update(),
    )
    if refresh_session is not None:
        await _revoke_refresh_session(session, refresh_session.session_id)
        await session.commit()
    return {"status": "ok"}


async def _verify_google_id_token(token: str, audience: str) -> dict[str, Any]:
    def verify() -> dict[str, Any]:
        verified_claims: Mapping[str, Any] = id_token.verify_oauth2_token(  # pyright: ignore[reportUnknownMemberType]
            token, requests.Request(), audience
        )
        claims = dict(verified_claims)
        if claims.get("iss") not in {"accounts.google.com", "https://accounts.google.com"}:
            raise ValueError("invalid issuer")
        if claims.get("aud") != audience:
            raise ValueError("invalid audience")
        if not claims.get("sub"):
            raise ValueError("missing subject")
        return claims

    try:
        return await asyncio.to_thread(verify)
    except ValueError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid Google token") from exc


def _build_session(
    settings: Settings,
    user_id: uuid.UUID,
    session_id: uuid.UUID | None = None,
    expires_at: datetime | None = None,
) -> tuple[RefreshSessionModel, AuthTokens]:
    refresh_token = new_refresh_token()
    refresh_session = RefreshSessionModel(
        session_id=session_id or uuid.uuid4(),
        user_id=user_id,
        token_hash=hash_refresh_token(refresh_token),
        expires_at=expires_at or now_utc() + timedelta(days=settings.refresh_session_days),
    )
    return refresh_session, AuthTokens(
        access_token=create_access_token(settings, user_id),
        refresh_token=refresh_token,
        expires_in=settings.access_token_minutes * 60,
    )


def _auth_identity_by_provider(provider: str, subject: str) -> Select[tuple[AuthIdentityModel]]:
    return select(AuthIdentityModel).where(
        AuthIdentityModel.provider == provider,
        AuthIdentityModel.provider_subject == subject,
    )


async def _revoke_refresh_session(
    session: AsyncSession, session_id: uuid.UUID, *, reused: bool = False
) -> None:
    now = now_utc()
    result = await session.execute(
        select(RefreshSessionModel)
        .where(RefreshSessionModel.session_id == session_id)
        .with_for_update()
    )
    for refresh_session in result.scalars():
        refresh_session.revoked_at = refresh_session.revoked_at or now
        if reused:
            refresh_session.reused_at = refresh_session.reused_at or now


async def _scalar[T](session: AsyncSession, statement: Select[tuple[T]]) -> T | None:
    result = await session.execute(statement)
    return result.scalar_one_or_none()
