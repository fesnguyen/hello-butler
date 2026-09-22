from __future__ import annotations

import uuid
from datetime import datetime, timedelta

from sqlalchemy import Select, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.application.auth.contracts import (
    AuthConflict,
    AuthInvalidCredentials,
    AuthTokens,
    AuthUnavailable,
    GoogleIdentityVerifier,
)
from app.core.config import Settings
from app.core.security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    new_refresh_token,
    normalize_email,
    now_utc,
    verify_password,
)
from app.infrastructure.db.models import AuthIdentityModel, RefreshSessionModel, UserModel


class AuthService:
    def __init__(self, settings: Settings, google_verifier: GoogleIdentityVerifier) -> None:
        self._settings = settings
        self._google_verifier = google_verifier

    async def register(self, session: AsyncSession, email: str, password: str) -> AuthTokens:
        email = normalize_email(email)
        if len(password) < self._settings.password_min_length:
            raise AuthInvalidCredentials("Password does not meet requirements")
        if await _scalar(session, _identity_by_provider("password", email)):
            raise AuthConflict("Email is already registered")

        user_id = uuid.uuid4()
        session.add(
            UserModel(
                id=user_id,
                email=email,
                credits=self._settings.butler_initial_credits,
            )
        )
        identity = AuthIdentityModel(
            user_id=user_id,
            provider="password",
            provider_subject=email,
            email=email,
            email_verified=False,
            password_hash=hash_password(password),
        )
        try:
            await session.flush()
            session.add(identity)
            await session.flush()
        except IntegrityError as exc:
            await session.rollback()
            raise AuthConflict("Email is already registered") from exc

        refresh_session, tokens = self._build_session(user_id)
        session.add(refresh_session)
        await session.commit()
        return tokens

    async def login(self, session: AsyncSession, email: str, password: str) -> AuthTokens:
        email = normalize_email(email)
        identity = await _scalar(session, _identity_by_provider("password", email))
        if (
            identity is None
            or identity.password_hash is None
            or not verify_password(identity.password_hash, password)
        ):
            raise AuthInvalidCredentials("Invalid email or password")

        refresh_session, tokens = self._build_session(identity.user_id)
        session.add(refresh_session)
        await session.commit()
        return tokens

    async def google(self, session: AsyncSession, token: str) -> AuthTokens:
        if not self._settings.google_oauth_client_id:
            raise AuthUnavailable("Google authentication is not configured")
        try:
            claims = await self._google_verifier.verify(
                token, self._settings.google_oauth_client_id
            )
        except ValueError as exc:
            raise AuthInvalidCredentials("Invalid Google token") from exc

        provider_subject = str(claims["sub"])
        email = normalize_email(str(claims["email"])) if claims.get("email") else None
        email_verified = claims.get("email_verified") is True
        if email is None or not email_verified:
            raise AuthInvalidCredentials("Invalid Google token")

        identity = await _scalar(session, _identity_by_provider("google", provider_subject))
        if identity is None:
            if await _scalar(session, select(UserModel).where(UserModel.email == email)):
                raise AuthConflict("Google account is not linked")

            user_id = uuid.uuid4()
            name = claims.get("name")
            session.add(
                UserModel(
                    id=user_id,
                    email=email,
                    display_name=name if isinstance(name, str) else None,
                    credits=self._settings.butler_initial_credits,
                )
            )
            identity = AuthIdentityModel(
                user_id=user_id,
                provider="google",
                provider_subject=provider_subject,
                email=email,
                email_verified=True,
            )
            try:
                await session.flush()
                session.add(identity)
                await session.flush()
            except IntegrityError as exc:
                await session.rollback()
                raise AuthConflict("Google account is not linked") from exc

        refresh_session, tokens = self._build_session(identity.user_id)
        session.add(refresh_session)
        await session.commit()
        return tokens

    async def refresh(self, session: AsyncSession, refresh_token: str) -> AuthTokens:
        refresh_session = await _scalar(
            session,
            select(RefreshSessionModel)
            .where(RefreshSessionModel.token_hash == hash_refresh_token(refresh_token))
            .with_for_update(),
        )
        if refresh_session is None:
            raise AuthInvalidCredentials("Invalid refresh token")

        now = now_utc()
        if (
            refresh_session.revoked_at is not None
            or refresh_session.consumed_at is not None
            or refresh_session.expires_at <= now
        ):
            await _revoke_refresh_session(session, refresh_session.session_id, reused=True)
            await session.commit()
            raise AuthInvalidCredentials("Invalid refresh token")

        replacement, tokens = self._build_session(
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
        return tokens

    async def logout(self, session: AsyncSession, refresh_token: str) -> None:
        refresh_session = await _scalar(
            session,
            select(RefreshSessionModel)
            .where(RefreshSessionModel.token_hash == hash_refresh_token(refresh_token))
            .with_for_update(),
        )
        if refresh_session is not None:
            await _revoke_refresh_session(session, refresh_session.session_id)
            await session.commit()

    def _build_session(
        self,
        user_id: uuid.UUID,
        session_id: uuid.UUID | None = None,
        expires_at: datetime | None = None,
    ) -> tuple[RefreshSessionModel, AuthTokens]:
        refresh_token = new_refresh_token()
        refresh_session = RefreshSessionModel(
            session_id=session_id or uuid.uuid4(),
            user_id=user_id,
            token_hash=hash_refresh_token(refresh_token),
            expires_at=expires_at
            or now_utc() + timedelta(days=self._settings.refresh_session_days),
        )
        return refresh_session, AuthTokens(
            access_token=create_access_token(self._settings, user_id),
            refresh_token=refresh_token,
            expires_in=self._settings.access_token_minutes * 60,
        )


def _identity_by_provider(provider: str, subject: str) -> Select[tuple[AuthIdentityModel]]:
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
