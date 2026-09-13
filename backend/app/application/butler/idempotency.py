from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession

from app.application.butler.contracts import ButlerResult
from app.application.butler.state import ButlerState
from app.infrastructure.db.models import ButlerActionReceiptModel


async def prior_action_result(session: AsyncSession, state: ButlerState) -> ButlerResult | None:
    request_id = state.get("request_id")
    if request_id is None:
        return None
    receipt = await session.get(ButlerActionReceiptModel, request_id)
    return ButlerResult.model_validate(receipt.result) if receipt else None


def save_action_result(session: AsyncSession, state: ButlerState, result: ButlerResult) -> None:
    request_id = state.get("request_id")
    if request_id is not None:
        session.add(
            ButlerActionReceiptModel(
                request_id=request_id,
                result=result.model_dump(mode="json"),
            )
        )
