"""Persistence guard for stable dates, not a general natural-language parser.

The model resolves broader language using the supplied local datetime. Known forms
are normalized here; ambiguous/contradictory forms reject the mutation. Old records
are touched only by an explicit edit, never by a migration or planning read.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from app.infrastructure.db.models import UserContextEntryModel

_WORDS = dict(
    zip(
        [
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
        ],
        range(1, 15),
        strict=True,
    )
)
_MONTHS = dict(
    zip(
        [
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december",
        ],
        range(1, 13),
        strict=True,
    )
)
_MONTH = "(?:" + "|".join(_MONTHS) + ")"
_ISO = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
_MONTH_DATE = re.compile(rf"\b({_MONTH})\s+(\d{{1,2}})(?:\s*[, ]\s*(\d{{4}}))?\b", re.I)
_MONTH_RANGE = re.compile(
    rf"\b({_MONTH})\s+(\d{{1,2}})\s*[–—-]\s*(\d{{1,2}}),?\s+(\d{{4}})\b", re.I
)
_RELATIVE = re.compile(
    r"\b(today|tomorrow|yesterday|(?:next|this) (?:week|month)|in (?:\d+|[a-z]+) days?)\b", re.I
)
_RELATIVE_TIME = re.compile(r"\bin (\d+|[a-z]+) (minutes?|hours?)\b", re.I)
_DURATION = re.compile(r"\bfor (?:the )?(?:next )?(\d+|[a-z]+) (days?|weeks?)\b", re.I)
_WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


@dataclass(frozen=True)
class ContextPeriod:
    content: str
    starts_on: date | None
    ends_on: date | None
    recurrence: str | None
    start_time: time | None = None

    @classmethod
    def of(cls, entry: UserContextEntryModel) -> ContextPeriod:
        return cls(
            entry.content, entry.starts_on, entry.ends_on, entry.recurrence, entry.start_time
        )


def _count(word: str) -> int:
    number = int(word) if word.isdigit() else _WORDS.get(word.lower(), 0)
    if not 1 <= number <= 3660:
        raise ValueError("Temporal duration cannot be resolved reliably")
    return number


def _absolute_text(content: str, boundaries: list[date]) -> str:
    def period(match: re.Match[str]) -> str:
        month, first, last, year = match.groups()
        start = date(int(year), _MONTHS[month.lower()], int(first)).isoformat()
        end = date(int(year), _MONTHS[month.lower()], int(last)).isoformat()
        return f"{start} through {end}"

    content = _MONTH_RANGE.sub(period, content)

    def point(match: re.Match[str]) -> str:
        month, day, year = match.groups()
        if year is None:
            years = {
                bound.year
                for bound in boundaries
                if (bound.month, bound.day) == (_MONTHS[month.lower()], int(day))
            }
            if len(years) != 1:
                raise ValueError("A one-time calendar date requires an explicit year")
            year = str(years.pop())
        return date(int(year), _MONTHS[month.lower()], int(day)).isoformat()

    return _MONTH_DATE.sub(point, content)


def _recurring_rule(content: str) -> tuple[str, list[int]] | None:
    match = re.search(
        r"\b(every day|every (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
        r"|on weekdays)\b",
        content,
        re.I,
    )
    if match is None:
        return None
    wording = match[0].lower()
    return (
        ("daily", [])
        if wording == "every day"
        else (
            "weekly",
            list(range(5))
            if wording == "on weekdays"
            else [
                index
                for index, day in enumerate(_WEEKDAYS)
                if re.search(r"\b" + day + r"\b", content, re.I)
            ],
        )
    )


def normalize_context_period(
    entry: UserContextEntryModel,
    *,
    now: datetime | None = None,
    previous: ContextPeriod | None = None,
    changed_fields: set[str] | None = None,
    source_text: str | None = None,
) -> None:
    fields = changed_fields or set()
    entry.content = entry.content.strip()
    if not entry.content:
        raise ValueError("User Context requires content")
    original = entry.content
    end_explicit = "ends_on" in fields or (previous is None and entry.ends_on is not None)
    if (
        previous
        and original != previous.content
        and fields == {"content"}
        and (
            _RELATIVE.search(original)
            or _RELATIVE_TIME.search(original)
            or re.search(r"\bfor (?:the )?next (?:\d+|[a-z]+) (?:days?|weeks?)\b", original, re.I)
        )
    ):
        entry.starts_on = entry.ends_on = None
        if _RELATIVE_TIME.search(original):
            entry.start_time = None
    rule = _recurring_rule(original)
    if rule and entry.recurrence is None:
        entry.recurrence, entry.recurrence_days = rule
    old_start, old_end = (previous.starts_on, previous.ends_on) if previous else (None, None)
    if previous and old_start and old_end and not previous.recurrence:
        duration = old_end - old_start
        if "starts_on" in fields and "ends_on" not in fields and entry.starts_on:
            entry.ends_on = entry.starts_on + duration
        elif "ends_on" in fields and "starts_on" not in fields and entry.ends_on:
            entry.starts_on = entry.ends_on - duration
    bounds = [bound for bound in (entry.starts_on, entry.ends_on) if bound]
    old_bounds = [bound for bound in (old_start, old_end) if bound]
    if previous and original == previous.content and bounds != old_bounds:
        entry.content = _absolute_text(entry.content, old_bounds)
        replacements = {
            old.isoformat(): new.isoformat()
            for old, new in ((old_start, entry.starts_on), (old_end, entry.ends_on))
            if old and new
        }
        entry.content = _ISO.sub(lambda match: replacements.get(match[0], match[0]), entry.content)
    entry.content = _absolute_text(entry.content, bounds)
    for match in list(_RELATIVE.finditer(entry.content))[::-1]:
        phrase = match[0].lower()
        if previous and original == previous.content:
            if not old_start:
                raise ValueError("Legacy relative context has no reliable date anchor")
            resolved = entry.starts_on or old_start
        else:
            if now is None or now.tzinfo is None:
                raise ValueError("Relative context requires current local datetime and timezone")
            offsets = {"today": 0, "tomorrow": 1, "yesterday": -1}
            if phrase in offsets:
                resolved = now.date() + timedelta(days=offsets[phrase])
            elif phrase.startswith("in "):
                resolved = now.date() + timedelta(days=_count(phrase.split()[1]))
            elif entry.starts_on and entry.ends_on:
                resolved = entry.starts_on
            else:
                raise ValueError("Ambiguous relative period requires resolved boundaries")
        if entry.starts_on and not (
            entry.starts_on <= resolved <= (entry.ends_on or entry.starts_on)
        ):
            raise ValueError("Context text contradicts its resolved period")
        entry.content = (
            entry.content[: match.start()] + resolved.isoformat() + entry.content[match.end() :]
        )
        if entry.starts_on is None:
            entry.starts_on = resolved
            if not entry.recurrence:
                entry.ends_on = entry.ends_on or resolved
    for match in list(_RELATIVE_TIME.finditer(entry.content))[::-1]:
        if previous and original == previous.content:
            if not previous.starts_on or not previous.start_time:
                raise ValueError("Legacy relative time has no reliable anchor")
            day, clock = entry.starts_on or previous.starts_on, previous.start_time
        else:
            if now is None or now.tzinfo is None:
                raise ValueError("Relative time requires current local datetime and timezone")
            resolved_time = now + timedelta(
                minutes=_count(match[1]) * (60 if match[2].startswith("hour") else 1)
            )
            day, clock = resolved_time.date(), resolved_time.time().replace(second=0, microsecond=0)
        if (entry.starts_on and entry.starts_on != day) or (
            entry.start_time and entry.start_time != clock
        ):
            raise ValueError("Context relative time contradicts structured fields")
        entry.starts_on, entry.start_time = day, clock
        if not entry.recurrence:
            entry.ends_on = entry.ends_on or day
        entry.content = (
            entry.content[: match.start()]
            + f"{day.isoformat()} at {clock.isoformat(timespec='minutes')}"
            + entry.content[match.end() :]
        )
    if source_text and not entry.recurrence and now and now.tzinfo:
        points = list(_RELATIVE.finditer(source_text))
        weekdays = [
            index
            for index, day in enumerate(_WEEKDAYS)
            if re.search(r"\b" + day + r"\b", source_text, re.I)
        ]
        if (
            entry.starts_on
            and len(points) == 1
            and not weekdays
            and (previous is None or "starts_on" in fields)
        ):
            phrase = points[0][0].lower()
            offsets = {"today": 0, "tomorrow": 1, "yesterday": -1}
            expected = (
                now.date() + timedelta(days=offsets[phrase])
                if phrase in offsets
                else (
                    now.date() + timedelta(days=_count(phrase.split()[1]))
                    if phrase.startswith("in ")
                    else None
                )
            )
            if expected and entry.starts_on != expected:
                raise ValueError("Context start contradicts the user's relative date")
        if (
            entry.starts_on
            and "starts_on" in fields
            and len(weekdays) == 1
            and not points
            and entry.starts_on.weekday() != weekdays[0]
        ):
            raise ValueError("Context start contradicts the user's weekday")
    duration_match = _DURATION.search(source_text or original)
    if duration_match:
        wording = source_text or original
        if (
            now
            and now.tzinfo
            and "next" in duration_match[0].lower()
            and not _RELATIVE.search(wording)
            and not _ISO.search(wording)
            and not _MONTH_DATE.search(wording)
            and not any(re.search(r"\b" + day + r"\b", wording, re.I) for day in _WEEKDAYS)
            and (previous is None or original != previous.content)
            and entry.starts_on
            and entry.starts_on != now.date()
        ):
            raise ValueError("Temporary relative period must start at the supplied local date")
        days = _count(duration_match[1]) * (7 if duration_match[2].startswith("week") else 1)
        if entry.starts_on is None:
            if now is None or now.tzinfo is None or "next" not in duration_match[0].lower():
                raise ValueError("Duration requires a reliable start date")
            entry.starts_on = now.date()
        expected_end = entry.starts_on + timedelta(days=days - 1)
        if end_explicit and entry.ends_on and entry.ends_on != expected_end:
            raise ValueError("Context period must preserve the requested inclusive duration")
        entry.ends_on = expected_end
        entry.content = re.sub(
            r"\bfor (?:the )?next (?:\d+|[a-z]+) (?:days?|weeks?)\b",
            f"from {entry.starts_on.isoformat()} through {expected_end.isoformat()}",
            entry.content,
            flags=re.I,
        )
    if rule and (
        entry.recurrence != rule[0]
        or (rule[0] == "weekly" and sorted(entry.recurrence_days or []) != rule[1])
    ):
        if previous and original != previous.content and "recurrence_days" not in fields:
            entry.recurrence, entry.recurrence_days = rule
        else:
            raise ValueError("Context recurrence contradicts its stored description")
    if entry.recurrence == "weekly" and not entry.recurrence_days:
        raise ValueError("Weekly context requires recurrence days")
    if any(day not in range(7) for day in (entry.recurrence_days or [])):
        raise ValueError("Invalid recurrence weekday")
    if not entry.recurrence and re.search(
        r"\b(?:next|this|starting|start on|on)\s+(?:" + "|".join(_WEEKDAYS) + r")\b",
        entry.content,
        re.I,
    ):
        raise ValueError("A one-time weekday must be resolved to an explicit calendar date")
    if entry.starts_on and entry.ends_on and entry.ends_on < entry.starts_on:
        raise ValueError("Context end date must not precede its start date")
    if entry.start_time and entry.end_time and entry.end_time <= entry.start_time:
        raise ValueError("Context end time must be after its start time")
    dates = [date.fromisoformat(match[0]) for match in _ISO.finditer(entry.content)]
    if (
        entry.starts_on
        and dates
        and any(
            not (entry.starts_on <= point <= (entry.ends_on or entry.starts_on)) for point in dates
        )
    ):
        raise ValueError("Context content and structured dates disagree")
    if (
        entry.context_type in ("temporary", "one_time")
        and not entry.recurrence
        and not entry.starts_on
        and dates
    ):
        entry.starts_on, entry.ends_on = min(dates), max(dates)
    if entry.starts_on and not entry.recurrence:
        start, end = entry.starts_on.isoformat(), (entry.ends_on or entry.starts_on).isoformat()
        if start not in entry.content or (entry.ends_on and end not in entry.content):
            entry.content += (
                f" (valid {start} through {end})" if entry.ends_on else f" (valid from {start})"
            )
    if entry.context_type in ("note", "preference") and entry.is_actionable:
        raise ValueError("Notes and preferences cannot be actionable calendar events")
