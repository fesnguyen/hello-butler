from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

engine = create_async_engine(get_settings().showcase_database_url, pool_pre_ping=True)
ShowcaseSessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_showcase_session() -> AsyncGenerator[AsyncSession]:
    async with ShowcaseSessionLocal() as session:
        yield session
