from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "Hello Butler Backend"
    app_env: str = "development"
    database_url: str = Field(
        default="postgresql+asyncpg://hello_butler:hello_butler@localhost:5433/hello_butler"
    )

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
