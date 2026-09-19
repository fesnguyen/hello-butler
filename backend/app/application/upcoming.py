from __future__ import annotations

import uuid
from datetime import date, time, timedelta
from typing import Annotated, Literal

from pydantic import BaseModel, Field, model_validator
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.infrastructure.db.models import UserContextEntryModel

RecurringPattern = Literal["daily", "weekly"]
UpcomingAction = Literal["modify", "reschedule", "skip", "remove"]
UpcomingScope = Literal["occurrence", "rule"]
ROUTINE_TYPES = frozenset({"routine_daily", "routine_weekly"})


class OccurrenceException(BaseModel):
    occurrence_date: date
    action: Literal["modify", "reschedule", "skip", "remove"]
    replacement_date: date | None = None
    title: str | None = None
    start_time: time | None = None
    end_time: time | None = None


class UpcomingEvent(BaseModel):
    id: str
    source_context_id: uuid.UUID
    source_context_version: int
    occurrence_date: date | None = None
    title: str
    description: str
    starts_on: date
    ends_on: date
    start_time: time | None = None
    end_time: time | None = None
    recurring: bool


class UpcomingEventMutation(BaseModel):
    action: UpcomingAction
    scope: UpcomingScope
    base_version: Annotated[int, Field(ge=1)]
    occurrence_date: date | None = None
    title: Annotated[str | None, Field(min_length=1, max_length=200)] = None
    starts_on: date | None = None
    ends_on: date | None = None
    start_time: time | None = None
    end_time: time | None = None

    @model_validator(mode="after")
    def valid_scope(self) -> UpcomingEventMutation:
        if self.scope == "occurrence" and self.occurrence_date is None:
            raise ValueError("occurrence_date is required for an occurrence change")
        if self.action == "reschedule" and self.starts_on is None:
            raise ValueError("starts_on is required when rescheduling")
        if (
            self.ends_on is not None
            and self.starts_on is not None
            and self.ends_on < self.starts_on
        ):
            raise ValueError("ends_on must not be before starts_on")
        if (
            self.end_time is not None
            and self.start_time is not None
            and self.end_time <= self.start_time
        ):
            raise ValueError("end_time must be after start_time")
        return self


class UpcomingMutationResult(BaseModel):
    source_context_id: uuid.UUID
    source_context_version: int
    upcoming_events: list[UpcomingEvent]


def project_upcoming(
    rows: list[UserContextEntryModel], today: date, *, horizon_days: int = 31
) -> list[UpcomingEvent]:
    events: list[UpcomingEvent] = []
    horizon = today + timedelta(days=horizon_days)
    for row in rows:
        if (
            not row.is_actionable
            or row.context_type in ROUTINE_TYPES
            or row.starts_on is None
            or (row.ends_on is not None and row.ends_on < today)
        ):
            continue
        title = row.title or row.content
        end = row.ends_on or row.starts_on
        if row.recurrence in {"daily", "weekly"}:
            final = min(end, horizon) if row.ends_on else horizon
            current = max(today, row.starts_on)
            exceptions = {
                item.occurrence_date: item
                for item in (
                    OccurrenceException.model_validate(value)
                    for value in (row.occurrence_exceptions or [])
                )
            }
            while current <= final:
                applies = row.recurrence == "daily" or current.weekday() in row.recurrence_days
                if applies:
                    exception = exceptions.get(current)
                    if exception is None or exception.action not in {"skip", "remove"}:
                        shown_date = exception.replacement_date if exception else current
                        events.append(
                            _projection(
                                row,
                                occurrence_date=current,
                                starts_on=shown_date or current,
                                ends_on=shown_date or current,
                                title=exception.title if exception and exception.title else title,
                                start_time=(
                                    exception.start_time
                                    if exception and exception.start_time is not None
                                    else row.start_time
                                ),
                                end_time=(
                                    exception.end_time
                                    if exception and exception.end_time is not None
                                    else row.end_time
                                ),
                            )
                        )
                current += timedelta(days=1)
        else:
            events.append(
                _projection(
                    row,
                    occurrence_date=None,
                    starts_on=row.starts_on,
                    ends_on=end,
                    title=title,
                    start_time=row.start_time,
                    end_time=row.end_time,
                )
            )
    return sorted(
        events, key=lambda item: (item.starts_on, item.start_time or time.max, item.title)
    )


def _projection(
    row: UserContextEntryModel,
    *,
    occurrence_date: date | None,
    starts_on: date,
    ends_on: date,
    title: str,
    start_time: time | None,
    end_time: time | None,
) -> UpcomingEvent:
    key = occurrence_date.isoformat() if occurrence_date else f"{starts_on}:{ends_on}"
    return UpcomingEvent(
        id=f"{row.id}:{key}",
        source_context_id=row.id,
        source_context_version=row.version,
        occurrence_date=occurrence_date,
        title=title,
        description=row.content,
        starts_on=starts_on,
        ends_on=ends_on,
        start_time=start_time,
        end_time=end_time,
        recurring=row.recurrence in {"daily", "weekly"},
    )


async def load_upcoming(
    session: AsyncSession, user_id: uuid.UUID, today: date
) -> list[UpcomingEvent]:
    rows = list(
        (
            await session.execute(
                select(UserContextEntryModel).where(
                    UserContextEntryModel.user_id == user_id,
                    UserContextEntryModel.deleted_at.is_(None),
                    UserContextEntryModel.is_actionable.is_(True),
                    UserContextEntryModel.starts_on.is_not(None),
                    or_(
                        UserContextEntryModel.ends_on.is_(None),
                        UserContextEntryModel.ends_on >= today,
                    ),
                )
            )
        ).scalars()
    )
    return project_upcoming(rows, today)


class UpcomingEventService:
    async def mutate(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        context_id: uuid.UUID,
        mutation: UpcomingEventMutation,
        today: date,
    ) -> UpcomingMutationResult:
        row = (
            await session.execute(
                select(UserContextEntryModel)
                .where(
                    UserContextEntryModel.id == context_id,
                    UserContextEntryModel.user_id == user_id,
                    UserContextEntryModel.deleted_at.is_(None),
                )
                .with_for_update()
            )
        ).scalar_one_or_none()
        if row is None:
            raise LookupError("Upcoming Event source context was not found")
        if not row.is_actionable or row.context_type in ROUTINE_TYPES:
            raise ValueError("User Context is not an Upcoming Event source")
        if row.version != mutation.base_version:
            raise RuntimeError("Upcoming Event source changed; refresh and retry")

        if mutation.scope == "occurrence":
            self._mutate_occurrence(row, mutation)
        else:
            self._mutate_rule(row, mutation)
        row.version += 1
        await session.flush()
        return UpcomingMutationResult(
            source_context_id=row.id,
            source_context_version=row.version,
            upcoming_events=await load_upcoming(session, user_id, today),
        )

    @staticmethod
    def _mutate_occurrence(row: UserContextEntryModel, mutation: UpcomingEventMutation) -> None:
        if row.recurrence not in {"daily", "weekly"}:
            raise ValueError("Occurrence changes require recurring User Context")
        if mutation.occurrence_date is None:  # The validated occurrence contract guarantees it.
            raise ValueError("Occurrence date is required")
        exception = OccurrenceException(
            occurrence_date=mutation.occurrence_date,
            action=mutation.action,
            replacement_date=mutation.starts_on if mutation.action == "reschedule" else None,
            title=mutation.title,
            start_time=mutation.start_time,
            end_time=mutation.end_time,
        )
        existing = [
            value
            for value in (row.occurrence_exceptions or [])
            if value.get("occurrence_date") != mutation.occurrence_date.isoformat()
        ]
        row.occurrence_exceptions = [*existing, exception.model_dump(mode="json")]

    @staticmethod
    def _mutate_rule(row: UserContextEntryModel, mutation: UpcomingEventMutation) -> None:
        if mutation.action == "remove":
            from datetime import UTC, datetime

            row.deleted_at = datetime.now(UTC)
            return
        if mutation.action == "skip":
            raise ValueError("Skipping requires an occurrence scope")
        if mutation.title is not None:
            row.title = mutation.title.strip()
        if mutation.starts_on is not None:
            row.starts_on = mutation.starts_on
        if mutation.ends_on is not None:
            row.ends_on = mutation.ends_on
        if mutation.start_time is not None:
            row.start_time = mutation.start_time
        if mutation.end_time is not None:
            row.end_time = mutation.end_time
