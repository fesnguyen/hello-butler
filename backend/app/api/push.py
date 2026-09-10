from typing import Annotated

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.push import PushService
from app.core.config import Settings, get_settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.push import FirebasePushProvider

router = APIRouter(prefix="/api/push", tags=["push"])
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]
SettingsDep = Annotated[Settings, Depends(get_settings)]


class PushDeviceRequest(BaseModel):
    registration_token: str = Field(min_length=20, max_length=512)


def _service(settings: Settings) -> PushService:
    return PushService(
        AsyncSessionLocal,
        FirebasePushProvider(
            project_id=settings.firebase_project_id,
            credentials_path=settings.firebase_credentials_path,
        ),
    )


@router.put("/device", status_code=status.HTTP_204_NO_CONTENT)
async def register_device(
    request: PushDeviceRequest, user: AuthenticatedUserDep, settings: SettingsDep
) -> None:
    await _service(settings).register(user.id, request.registration_token)


@router.delete("/device", status_code=status.HTTP_204_NO_CONTENT)
async def unregister_device(
    request: PushDeviceRequest, user: AuthenticatedUserDep, settings: SettingsDep
) -> None:
    await _service(settings).unregister(user.id, request.registration_token)
