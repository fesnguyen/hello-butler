from datetime import time
from functools import lru_cache
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "Hello Butler Backend"
    app_env: str = "development"
    database_url: str = Field(
        default="postgresql+asyncpg://hello_butler:hello_butler@localhost:5433/hello_butler"
    )
    jwt_secret: str
    jwt_algorithm: str = "HS256"
    jwt_issuer: str = "hello-butler"
    jwt_audience: str = "hello-butler-api"
    access_token_minutes: int = 15
    refresh_session_days: int = 30
    google_oauth_client_id: str = ""
    password_min_length: int = 8
    openai_api_key: str = ""
    openai_model: str = "gpt-5.4-nano"
    butler_audio_model: str = "gpt-audio-1.5"
    butler_audio_voice: str = "alloy"
    butler_audio_root: str = "data/butler_audio"
    butler_max_input_audio_bytes: int = 15 * 1024 * 1024
    butler_processing_stale_minutes: int = 10
    butler_maintenance_interval_seconds: int = 60
    butler_response_audio_retention_days: int = 7
    butler_input_audio_failure_retention_hours: int = 24
    butler_default_timezone: str = "UTC"
    butler_history_limit: int = 6
    butler_user_context_limit: int = 20
    butler_event_limit: int = 30
    nightly_planning_time: time = time(23, 0)
    morning_brief_default_time: time = time(6, 5)
    evening_preparation_time: time = time(22, 30)
    good_night_summary_time: time = time(22, 45)
    push_timeout_seconds: float = Field(default=5, gt=0, le=30)
    firebase_project_id: str = ""
    firebase_credentials_path: str = ""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @field_validator("jwt_secret")
    @classmethod
    def require_production_secret(cls, value: str) -> str:
        if not value:
            raise ValueError("jwt_secret is required")
        if len(value.encode("utf-8")) < 32:
            raise ValueError("jwt_secret must be at least 32 bytes")
        return value

    @field_validator("butler_default_timezone")
    @classmethod
    def require_valid_timezone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("butler_default_timezone must be an IANA timezone") from exc
        return value

    @property
    def timezone(self) -> ZoneInfo:
        return ZoneInfo(self.butler_default_timezone)


@lru_cache
def get_settings() -> Settings:
    return Settings()  # pyright: ignore[reportCallIssue]
