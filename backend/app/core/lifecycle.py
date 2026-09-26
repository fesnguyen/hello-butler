from app.application.butler.requests import ButlerRequestService
from app.application.butler.service import ButlerService
from app.application.push import PushService
from app.application.push.changes import DailyPlanChanges
from app.application.speech import SpeechService
from app.core.config import Settings
from app.core.database import AsyncSessionLocal
from app.infrastructure.ai.kokoro_provider import KokoroButlerVoiceProvider
from app.infrastructure.ai.openai_provider import (
    OpenAIButlerProvider,
    OpenAIButlerVoiceProvider,
)
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


def speech_service(settings: Settings) -> SpeechService:
    return SpeechService(
        settings,
        AsyncSessionLocal,
        OpenAIButlerVoiceProvider(
            api_key=settings.openai_api_key,
            model=settings.butler_tts_model,
            voice=settings.butler_audio_voice,
        ),
        KokoroButlerVoiceProvider(
            language=settings.butler_kokoro_language,
            voice=settings.butler_kokoro_voice,
        ),
    )


def butler_request_service(settings: Settings) -> ButlerRequestService:
    provider = OpenAIButlerProvider(
        api_key=settings.openai_api_key,
        model=settings.openai_model,
        audio_model=settings.butler_audio_model,
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
        push=push_service(settings),
        speech=speech_service(settings),
    )
