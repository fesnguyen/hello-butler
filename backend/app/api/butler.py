from __future__ import annotations

import asyncio
import logging
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
    """Accept, validate, persist, and queue an Ogg/Opus Butler voice request for processing."""

    # Reuse the client request ID for retries/idempotency, otherwise create a new request.
    request_id = request_id or uuid.uuid4()

    # Enforce the client-to-backend audio transport contract before storing the upload.
    declared_mime = (audio.content_type or "").split(";", 1)[0].lower()
    if declared_mime != "audio/ogg":
        raise HTTPException(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Voice input must be Ogg/Opus (audio/ogg)"
        )

    # Store input audio temporarily until the Butler request finishes processing.
    path = Path(settings.butler_audio_root) / "inputs" / f"{request_id}-{uuid.uuid4()}.ogg"
    path.parent.mkdir(parents=True, exist_ok=True)
    size = 0

    try:
        # Stream to disk in bounded chunks instead of loading the entire upload into memory.
        with path.open("wb") as target:
            while chunk := await audio.read(64 * 1024):
                size += len(chunk)
                if size > settings.butler_max_input_audio_bytes:
                    raise HTTPException(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "Audio is too large"
                    )
                target.write(chunk)

        if size == 0:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Audio is empty")

        # Verify the actual container/codec signature instead of trusting the declared MIME type.
        header = await asyncio.to_thread(_read_prefix, path, 256)
        if not header.startswith(b"OggS") or b"OpusHead" not in header:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Audio is not valid Ogg/Opus")

        logger.info(
            "Butler audio upload stored request_id=%s mime=%s bytes=%d path=%s format=ogg/opus",
            request_id,
            declared_mime,
            size,
            path,
        )

        # Persist the accepted request and its temporary input-audio location.
        service = butler_request_service(settings)
        accepted = await service.accept_audio(
            request_id=request_id,
            user_id=user.id,
            interaction_mode=interaction_mode,
            path=path,
            mime_type="audio/ogg",
        )

        # A duplicate/already-processing request does not need this newly uploaded copy.
        if accepted.status != "accepted":
            path.unlink(missing_ok=True)

    except ButlerRequestConflict as exc:
        # Never leave an orphaned temporary upload when the request ID conflicts.
        path.unlink(missing_ok=True)
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc)) from exc
    except Exception:
        # Any validation/storage failure cleans up the partial temporary input.
        path.unlink(missing_ok=True)
        raise

    # Return 202 immediately while Butler processing continues after the HTTP response.
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
    if path.suffix.lower() == ".ogg":
        media_type, extension = "audio/ogg", "ogg"
    elif path.suffix.lower() == ".wav":
        media_type, extension = "audio/wav", "wav"
    else:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Response audio format is unavailable")
    return FileResponse(
        path,
        media_type=media_type,
        filename=f"butler-{request_id}.{extension}",
    )


def _read_prefix(path: Path, size: int) -> bytes:
    with path.open("rb") as source:
        return source.read(size)
