import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
from jwt import InvalidTokenError

from app.core.config import Settings

_password_hasher = PasswordHasher()


def now_utc() -> datetime:
    return datetime.now(UTC)


def normalize_email(email: str) -> str:
    return email.strip().lower()


def hash_password(password: str) -> str:
    return _password_hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, password)
    except (InvalidHashError, VerificationError, VerifyMismatchError):
        return False


def create_access_token(settings: Settings, user_id: uuid.UUID) -> str:
    issued_at = now_utc()
    expires_at = issued_at + timedelta(minutes=settings.access_token_minutes)
    return jwt.encode(
        {
            "sub": str(user_id),
            "iss": settings.jwt_issuer,
            "aud": settings.jwt_audience,
            "iat": issued_at,
            "exp": expires_at,
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )


def decode_access_token(settings: Settings, token: str) -> uuid.UUID:
    try:
        claims: dict[str, Any] = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["sub", "iss", "aud", "exp"]},
        )
        return uuid.UUID(str(claims["sub"]))
    except (InvalidTokenError, ValueError) as exc:
        raise ValueError("invalid access token") from exc


def new_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
