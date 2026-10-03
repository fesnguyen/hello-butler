from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.application.showcase import Application, ShowcaseService
from app.core.showcase_database import get_showcase_session

router = APIRouter(prefix="/api/showcase", tags=["showcase"])
ShowcaseSessionDep = Annotated[AsyncSession, Depends(get_showcase_session)]


@router.get("/applications", response_model=list[Application])
async def list_applications(session: ShowcaseSessionDep) -> list[Application]:
    return await ShowcaseService().list_applications(session)
