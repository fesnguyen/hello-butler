from __future__ import annotations

import uuid
from collections.abc import Mapping
from typing import Any, Protocol

from pydantic import BaseModel


class AuthTokens(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class AuthenticatedUser(BaseModel):
    id: uuid.UUID
    email: str | None
    display_name: str | None


class GoogleIdentityVerifier(Protocol):
    async def verify(self, token: str, audience: str) -> Mapping[str, Any]: ...


class AuthError(RuntimeError):
    pass


class AuthInvalidCredentials(AuthError):
    pass


class AuthConflict(AuthError):
    pass


class AuthUnavailable(AuthError):
    pass
