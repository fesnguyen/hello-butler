from __future__ import annotations

import logging
import time
import uuid
from pathlib import Path
from typing import Annotated, Literal

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    File,
    Form,
    HTTPException,
    UploadFile,
    status,
)
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask

from app.api.auth import AuthenticatedUser, get_authenticated_user
from app.application.butler.requests import (
    ButlerRequestAccepted,
    ButlerRequestConflict,
    ButlerRequestResult,
)
from app.core.config import Settings, get_settings
from app.core.lifecycle import butler_request_service

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/butler/requests", tags=["butler"])
SettingsDep = Annotated[Settings, Depends(get_settings)]
AuthenticatedUserDep = Annotated[AuthenticatedUser, Depends(get_authenticated_user)]


def _log_audio_transfer_completed(request_id: uuid.UUID, size: int, started: float) -> None:
    logger.info(
        "Response audio transfer completed request_id=%s bytes=%d elapsed_ms=%d",
        request_id,
        size,
        round((time.perf_counter() - started) * 1000),
    )


class ButlerTextRequest(BaseModel):
    request_id: uuid.UUID = Field(default_factory=uuid.uuid4)
    message: str = Field(min_length=1, max_length=20_000)


@router.get("")
async def recent_requests(
    user: AuthenticatedUserDep, settings: SettingsDep
) -> list[ButlerRequestResult]:
    return await butler_request_service(settings).recent_results(user.id)


@router.post("/text", status_code=status.HTTP_202_ACCEPTED)
async def create_text_request(
    request: ButlerTextRequest,
    user: AuthenticatedUserDep,
    settings: SettingsDep,
    background_tasks: BackgroundTasks,
) -> ButlerRequestAccepted:
    service = butler_request_service(settings)
    try:
        accepted = await service.accept_text(
            request_id=request.request_id, user_id=user.id, message=request.message
        )
    except (ValueError, ButlerRequestConflict) as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    background_tasks.add_task(service.process, request.request_id)
    return accepted


@router.post("/audio", status_code=status.HTTP_202_ACCEPTED)
async def create_audio_request(
    user: AuthenticatedUserDep,
    settings: SettingsDep,
    background_tasks: BackgroundTasks,
    interaction_mode: Annotated[Literal["order", "talk"], Form()],
    audio: Annotated[UploadFile, File()],
    request_id: Annotated[uuid.UUID | None, Form()] = None,
) -> ButlerRequestAccepted:
    request_id = request_id or uuid.uuid4()
    started = time.perf_counter()
    mime_type = audio.content_type or "application/octet-stream"
    suffix = Path(audio.filename or "request.m4a").suffix[:12] or ".m4a"
    path = Path(settings.butler_audio_root) / "inputs" / f"{request_id}-{uuid.uuid4()}{suffix}"
    path.parent.mkdir(parents=True, exist_ok=True)
    size = 0
    try:
        with path.open("wb") as target:
            while chunk := await audio.read(64 * 1024):
                size += len(chunk)
                if size > settings.butler_max_input_audio_bytes:
                    logger.warning(
                        "Audio upload rejected request_id=%s mode=%s mime_type=%s bytes=%d "
                        "reason=too_large",
                        request_id,
                        interaction_mode,
                        mime_type,
                        size,
                    )
                    raise HTTPException(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "Audio is too large"
                    )
                target.write(chunk)
        if size == 0:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Audio is empty")
        service = butler_request_service(settings)
        accepted = await service.accept_audio(
            request_id=request_id,
            user_id=user.id,
            interaction_mode=interaction_mode,
            path=path,
            mime_type=mime_type,
        )
        if accepted.status != "accepted":
            path.unlink(missing_ok=True)
    except ButlerRequestConflict as exc:
        path.unlink(missing_ok=True)
        logger.warning(
            "Audio upload conflicted request_id=%s mode=%s mime_type=%s bytes=%d elapsed_ms=%d",
            request_id,
            interaction_mode,
            mime_type,
            size,
            round((time.perf_counter() - started) * 1000),
        )
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    except Exception as exc:
        path.unlink(missing_ok=True)
        logger.warning(
            "Audio upload failed request_id=%s mode=%s mime_type=%s bytes=%d "
            "elapsed_ms=%d error_type=%s",
            request_id,
            interaction_mode,
            mime_type,
            size,
            round((time.perf_counter() - started) * 1000),
            type(exc).__name__,
        )
        raise
    logger.info(
        "Audio upload accepted request_id=%s mode=%s mime_type=%s bytes=%d elapsed_ms=%d status=%s",
        request_id,
        interaction_mode,
        mime_type,
        size,
        round((time.perf_counter() - started) * 1000),
        accepted.status,
    )
    background_tasks.add_task(service.process, request_id)
    return accepted


@router.get("/{request_id}")
async def get_request(
    request_id: uuid.UUID, user: AuthenticatedUserDep, settings: SettingsDep
) -> ButlerRequestResult:
    result = await butler_request_service(settings).result(user.id, request_id)
    if result is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Butler request not found")
    return result


@router.get("/{request_id}/audio")
async def get_response_audio(
    request_id: uuid.UUID, user: AuthenticatedUserDep, settings: SettingsDep
) -> FileResponse:
    path = await butler_request_service(settings).audio_path(user.id, request_id)
    if path is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Response audio is unavailable")
    size = path.stat().st_size
    started = time.perf_counter()
    logger.info(
        "Response audio transfer started request_id=%s mime_type=audio/wav bytes=%d",
        request_id,
        size,
    )
    return FileResponse(
        path,
        media_type="audio/wav",
        filename=f"butler-{request_id}.wav",
        background=BackgroundTask(_log_audio_transfer_completed, request_id, size, started),
    )
