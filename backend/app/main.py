import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from app.api.auth import router as auth_router
from app.api.butler import router as butler_router
from app.api.planning import router as planning_router
from app.api.push import router as push_router
from app.api.sync import router as sync_router
from app.application.planning.scheduler import NightlyPlanningScheduler
from app.core.config import get_settings
from app.core.lifecycle import butler_request_service

logger = logging.getLogger(__name__)


async def health() -> dict[str, str]:
    return {"status": "ok"}


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None]:
    settings = get_settings()
    scheduler = NightlyPlanningScheduler(settings)
    task = asyncio.create_task(scheduler.run_forever(), name="nightly-planning")
    request_service = butler_request_service(settings)

    async def maintain_butler_requests() -> None:
        while True:
            try:
                for request_id in await request_service.recover():
                    asyncio.create_task(request_service.process(request_id))
                await request_service.cleanup_audio()
            except Exception:
                logger.exception("Butler request maintenance failed")
            await asyncio.sleep(settings.butler_maintenance_interval_seconds)

    maintenance = asyncio.create_task(maintain_butler_requests(), name="butler-request-maintenance")
    try:
        yield
    finally:
        task.cancel()
        maintenance.cancel()
        with suppress(asyncio.CancelledError):
            await task
        with suppress(asyncio.CancelledError):
            await maintenance


def create_app() -> FastAPI:
    settings = get_settings()
    logging.getLogger("app").setLevel(settings.log_level)
    app = FastAPI(title=settings.app_name, lifespan=lifespan)
    app.add_api_route("/health", health, methods=["GET"])
    app.include_router(auth_router)
    app.include_router(butler_router)
    app.include_router(planning_router)
    app.include_router(push_router)
    app.include_router(sync_router)

    return app


app = create_app()
