from functools import lru_cache

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

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @field_validator("jwt_secret")
    @classmethod
    def require_production_secret(cls, value: str) -> str:
        if not value:
            raise ValueError("jwt_secret is required")
        if len(value.encode("utf-8")) < 32:
            raise ValueError("jwt_secret must be at least 32 bytes")
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()  # pyright: ignore[reportCallIssue]
