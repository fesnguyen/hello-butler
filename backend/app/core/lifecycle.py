from app.application.butler.audio import OpenAIButlerAudioProvider
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
    butler = ButlerService(
        settings=settings,
        session_factory=AsyncSessionLocal,
        ai_provider=OpenAIButlerProvider(
            api_key=settings.openai_api_key,
            model=settings.openai_model,
        ),
        changes=daily_plan_changes(settings),
    )
    return ButlerRequestService(
        settings=settings,
        session_factory=AsyncSessionLocal,
        butler=butler,
        audio=OpenAIButlerAudioProvider(
            api_key=settings.openai_api_key,
            transcription_model=settings.butler_transcription_model,
            tts_model=settings.butler_tts_model,
            voice=settings.butler_tts_voice,
        ),
        push=push_service(settings),
    )
