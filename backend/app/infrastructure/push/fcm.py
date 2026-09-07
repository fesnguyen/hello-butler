# pyright: reportMissingTypeStubs=false, reportUnknownMemberType=false, reportUnknownVariableType=false
from __future__ import annotations

import asyncio
from collections.abc import Sequence

import firebase_admin
from firebase_admin import credentials, exceptions, messaging


class FirebasePushProvider:
    def __init__(self, *, project_id: str, credentials_path: str) -> None:
        self._project_id = project_id
        self._credentials_path = credentials_path

    async def send_data(self, tokens: Sequence[str], data: dict[str, str]) -> set[str]:
        if not self._project_id or not tokens:
            return set()
        return await asyncio.to_thread(self._send, tokens, data)

    def _send(self, tokens: Sequence[str], data: dict[str, str]) -> set[str]:
        app = self._app()
        invalid: set[str] = set()
        for start in range(0, len(tokens), 500):
            batch = list(tokens[start : start + 500])
            response = messaging.send_each_for_multicast(
                messaging.MulticastMessage(tokens=batch, data=data), app=app
            )
            for token, result in zip(batch, response.responses, strict=True):
                if result.success:
                    continue
                if isinstance(
                    result.exception,
                    (messaging.UnregisteredError, messaging.SenderIdMismatchError),
                ):
                    invalid.add(token)
        return invalid

    def _app(self) -> firebase_admin.App:
        try:
            return firebase_admin.get_app()
        except ValueError:
            credential = (
                credentials.Certificate(self._credentials_path)
                if self._credentials_path
                else credentials.ApplicationDefault()
            )
            try:
                return firebase_admin.initialize_app(credential, {"projectId": self._project_id})
            except exceptions.FirebaseError:
                raise
