from app.application.push import PushService
from app.application.push.changes import DailyPlanChanges
from app.core.config import Settings
from app.core.database import AsyncSessionLocal
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
