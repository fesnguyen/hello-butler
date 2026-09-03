from app.core.config import get_settings
from fastapi import FastAPI


async def health() -> dict[str, str]:
    return {"status": "ok"}


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title=settings.app_name)
    app.add_api_route("/health", health, methods=["GET"])

    return app


app = create_app()
