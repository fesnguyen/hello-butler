from datetime import date, datetime, time, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.butler import ButlerAIUnavailableError
from app.application.planning import DayPlanningService, PreparedDayResult
from app.application.planning.service import PlanningValidationError
from app.core.config import Settings, get_settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider

router = APIRouter(prefix="/api/planning", tags=["planning"])
SettingsDep = Annotated[Settings, Depends(get_settings)]
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]


class PrepareDayRequest(BaseModel):
    target_date: date | None = None
    morning_brief_time: time | None = None


@router.post("/prepare", response_model=PreparedDayResult)
async def prepare_day(
    request: PrepareDayRequest,
    user: AuthenticatedUserDep,
    settings: SettingsDep,
) -> PreparedDayResult:
    tomorrow = datetime.now(settings.timezone).date() + timedelta(days=1)
    if (
        request.target_date is not None
        and request.target_date != tomorrow
        and settings.app_env == "production"
    ):
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "Target date override is development-only",
        )
    if request.morning_brief_time is not None and settings.app_env == "production":
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "Morning Brief time override is development-only",
        )
    target_date = request.target_date or tomorrow
    service = DayPlanningService(
        settings,
        AsyncSessionLocal,
        OpenAIButlerProvider(api_key=settings.openai_api_key, model=settings.openai_model),
    )
    try:
        return await service.prepare(user.id, target_date, request.morning_brief_time)
    except ButlerAIUnavailableError as exc:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Planning AI is unavailable"
        ) from exc
    except PlanningValidationError as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
