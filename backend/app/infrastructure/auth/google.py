from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

from google.auth.transport import requests
from google.oauth2 import id_token


class GoogleIdentityVerifier:
    async def verify(self, token: str, audience: str) -> Mapping[str, Any]:
        def verify_sync() -> Mapping[str, Any]:
            verify_token = id_token.verify_oauth2_token  # pyright: ignore[reportUnknownMemberType]
            claims: Mapping[str, Any] = verify_token(token, requests.Request(), audience)
            if claims.get("iss") not in {"accounts.google.com", "https://accounts.google.com"}:
                raise ValueError("invalid issuer")
            if claims.get("aud") != audience:
                raise ValueError("invalid audience")
            if not claims.get("sub"):
                raise ValueError("missing subject")
            return claims

        return await asyncio.to_thread(verify_sync)
