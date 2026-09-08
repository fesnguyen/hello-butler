from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.butler import ButlerAIUnavailableError, ButlerResult, ButlerService
from app.core.config import Settings, get_settings
from app.core.database import AsyncSessionLocal
from app.core.lifecycle import daily_plan_changes
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider

router = APIRouter(prefix="/api/butler", tags=["butler"])
SettingsDep = Annotated[Settings, Depends(get_settings)]
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]


class ButlerTalkRequest(BaseModel):
    interaction_mode: Literal["order", "talk"]
    message: str = Field(min_length=1)


@router.post("/talk")
async def talk(
    request: ButlerTalkRequest,
    user: AuthenticatedUserDep,
    settings: SettingsDep,
) -> ButlerResult:
    provider = OpenAIButlerProvider(api_key=settings.openai_api_key, model=settings.openai_model)
    service = ButlerService(
        settings=settings,
        session_factory=AsyncSessionLocal,
        ai_provider=provider,
        changes=daily_plan_changes(settings),
    )
    try:
        return await service.handle(
            user_id=user.id,
            interaction_mode=request.interaction_mode,
            message=request.message,
        )
    except ButlerAIUnavailableError as exc:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Butler reasoning is unavailable"
        ) from exc
