from fastapi import FastAPI

from app.api.auth import router as auth_router
from app.api.butler import router as butler_router
from app.core.config import get_settings


async def health() -> dict[str, str]:
    return {"status": "ok"}


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title=settings.app_name)
    app.add_api_route("/health", health, methods=["GET"])
    app.include_router(auth_router)
    app.include_router(butler_router)

    return app


app = create_app()
