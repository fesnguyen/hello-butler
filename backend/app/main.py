import asyncio
from contextlib import asynccontextmanager, suppress
from collections.abc import AsyncIterator

from fastapi import FastAPI

from app.api.auth import router as auth_router
from app.api.butler import router as butler_router
from app.api.planning import router as planning_router
from app.api.sync import router as sync_router
from app.application.planning.scheduler import NightlyPlanningScheduler
from app.core.config import get_settings


async def health() -> dict[str, str]:
    return {"status": "ok"}


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    scheduler = NightlyPlanningScheduler(get_settings())
    task = asyncio.create_task(scheduler.run_forever(), name="nightly-planning")
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title=settings.app_name, lifespan=lifespan)
    app.add_api_route("/health", health, methods=["GET"])
    app.include_router(auth_router)
    app.include_router(butler_router)
    app.include_router(planning_router)
    app.include_router(sync_router)

    return app


app = create_app()
