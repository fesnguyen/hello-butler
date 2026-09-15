from app.application.butler.requests import ButlerRequestService
from app.application.butler.service import ButlerService
from app.application.push import PushService
from app.application.push.changes import DailyPlanChanges
from app.core.config import Settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider
from app.infrastructure.push import FirebasePushProvider


def daily_plan_changes(settings: Settings) -> DailyPlanChanges:
    return DailyPlanChanges(
        AsyncSessionLocal,
        PushService(
            AsyncSessionLocal,
            FirebasePushProvider(
                project_id=settings.firebase_project_id,
                credentials_path=settings.firebase_credentials_path,
            ),
        ),
        timeout_seconds=settings.push_timeout_seconds,
    )


def push_service(settings: Settings) -> PushService:
    return PushService(
        AsyncSessionLocal,
        FirebasePushProvider(
            project_id=settings.firebase_project_id,
            credentials_path=settings.firebase_credentials_path,
        ),
    )


def butler_request_service(settings: Settings) -> ButlerRequestService:
    provider = OpenAIButlerProvider(
        api_key=settings.openai_api_key,
        model=settings.openai_model,
        audio_model=settings.butler_audio_model,
        audio_voice=settings.butler_audio_voice,
    )
    butler = ButlerService(
        settings=settings,
        session_factory=AsyncSessionLocal,
        ai_provider=provider,
        changes=daily_plan_changes(settings),
    )
    return ButlerRequestService(
        settings=settings,
        session_factory=AsyncSessionLocal,
        butler=butler,
        audio=provider,
        push=push_service(settings),
    )
