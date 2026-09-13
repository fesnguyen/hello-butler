from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.application.butler.state import ButlerState, result_from
from app.infrastructure.db.models import ConversationMessageModel


class ButlerHistoryWriter:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def save(self, state: ButlerState) -> None:
        result = result_from(state)
        decision = state.get("decision")
        async with self._session_factory() as session, session.begin():
            request_id = state.get("request_id")
            user_message_id = uuid.uuid5(request_id, "user") if request_id else uuid.uuid4()
            butler_message_id = uuid.uuid5(request_id, "butler") if request_id else uuid.uuid4()
            messages = [
                ConversationMessageModel(
                    id=user_message_id,
                    user_id=state["user_id"],
                    role="user",
                    content=state["message"],
                    message_metadata={
                        "request_id": str(request_id) if request_id else None,
                        "interaction_mode": state["interaction_mode"],
                        "intent": decision.intent if decision else None,
                    },
                ),
                ConversationMessageModel(
                    id=butler_message_id,
                    user_id=state["user_id"],
                    role="butler",
                    content=result.response,
                    message_metadata={
                        "request_id": str(request_id) if request_id else None,
                        "requires_follow_up": result.requires_follow_up,
                        "changed_entities": [
                            entity.model_dump(mode="json") for entity in result.changed_entities
                        ],
                    },
                ),
            ]
            for message in messages:
                await session.merge(message)
