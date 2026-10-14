import unittest
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

from app.application.context_temporal import ContextPeriod, normalize_context_period
from app.infrastructure.db.models import UserContextEntryModel


class TemporalContextTests(unittest.TestCase):
    now = datetime(2026, 10, 13, 17, 30, tzinfo=ZoneInfo("Asia/Bangkok"))

    def entry(self, content, kind="reference", **kwargs):
        return UserContextEntryModel(
            content=content, context_type=kind, is_actionable=False, recurrence_days=[], **kwargs
        )

    def test_inclusive_duration_applies_to_every_context_type(self):
        for kind in ("preference", "note", "reference", "temporary", "one_time"):
            with self.subTest(kind=kind):
                entry = self.entry("Working from home for five days starting tomorrow", kind)
                normalize_context_period(entry, now=self.now)
                self.assertEqual(
                    (entry.starts_on, entry.ends_on), (date(2026, 10, 14), date(2026, 10, 18))
                )
                self.assertNotIn("tomorrow", entry.content)
                self.assertIn("2026-10-18", entry.content)

    def test_provided_timezone_and_clock_rollover(self):
        now = datetime(2026, 10, 13, 23, 30, tzinfo=ZoneInfo("Asia/Bangkok"))
        entry = self.entry("Call in two hours")
        normalize_context_period(entry, now=now)
        self.assertEqual((entry.starts_on, entry.start_time), (date(2026, 10, 14), time(1, 30)))
        self.assertIn("2026-10-14 at 01:30", entry.content)
        entry = self.entry("Meeting today")
        normalize_context_period(entry, now=now)
        self.assertEqual(entry.starts_on, now.date())

    def test_recurring_rule_and_relative_start_remain_unbounded(self):
        entry = self.entry("Work every Monday starting tomorrow", "note")
        normalize_context_period(entry, now=self.now)
        self.assertEqual((entry.recurrence, entry.recurrence_days), ("weekly", [0]))
        self.assertEqual(entry.starts_on, date(2026, 10, 14))
        self.assertIsNone(entry.ends_on)
        weekdays = self.entry("Exercise on weekdays")
        normalize_context_period(weekdays, now=self.now)
        self.assertEqual(weekdays.recurrence_days, [0, 1, 2, 3, 4])

    def test_temporary_preference_does_not_become_permanent(self):
        entry = self.entry("For the next two weeks, don't schedule morning meetings", "preference")
        normalize_context_period(entry, now=self.now)
        self.assertEqual((entry.starts_on, entry.ends_on), (date(2026, 10, 13), date(2026, 10, 26)))
        self.assertNotIn("next two weeks", entry.content)
        self.assertFalse(entry.is_actionable)

    def test_boundary_edit_preserves_five_day_period(self):
        entry = self.entry(
            "Working from home October 14–18, 2026.",
            "temporary",
            starts_on=date(2026, 10, 14),
            ends_on=date(2026, 10, 18),
        )
        previous = ContextPeriod.of(entry)
        entry.starts_on = date(2026, 10, 16)
        normalize_context_period(entry, previous=previous, changed_fields={"starts_on"})
        self.assertEqual(entry.ends_on, date(2026, 10, 20))
        self.assertIn("2026-10-16 through 2026-10-20", entry.content)
        self.assertNotIn("2026-10-18", entry.content)

    def test_resolved_dates_are_not_reanchored_on_later_edit(self):
        entry = self.entry(
            "WFH 2026-10-14 through 2026-10-18",
            "temporary",
            starts_on=date(2026, 10, 14),
            ends_on=date(2026, 10, 18),
        )
        previous = ContextPeriod.of(entry)
        entry.content += "; with laptop"
        normalize_context_period(
            entry, now=self.now.replace(month=11), previous=previous, changed_fields={"content"}
        )
        self.assertEqual((entry.starts_on, entry.ends_on), (previous.starts_on, previous.ends_on))

    def test_ambiguous_and_inconsistent_dates_reject_persistence(self):
        cases = [
            self.entry("Travel next week"),
            self.entry("Work starting Friday"),
            self.entry("Meeting October 14"),
            self.entry("Meeting on Friday"),
            self.entry("WFH 2026-10-18", starts_on=date(2026, 10, 14), ends_on=date(2026, 10, 16)),
            self.entry("WFH", starts_on=date(2026, 10, 18), ends_on=date(2026, 10, 14)),
            self.entry(
                "WFH for five days starting tomorrow",
                starts_on=date(2026, 10, 14),
                ends_on=date(2026, 10, 19),
            ),
        ]
        for entry in cases:
            with self.subTest(content=entry.content), self.assertRaises(ValueError):
                normalize_context_period(entry, now=self.now)

    def test_model_date_and_weekday_must_match_user_request(self):
        entry = self.entry(
            "WFH 2026-10-15 through 2026-10-19",
            "temporary",
            starts_on=date(2026, 10, 15),
            ends_on=date(2026, 10, 19),
        )
        with self.assertRaises(ValueError):
            normalize_context_period(
                entry, now=self.now, source_text="For five days starting tomorrow"
            )
        entry.starts_on, entry.ends_on = date(2026, 10, 17), date(2026, 10, 21)
        with self.assertRaises(ValueError):
            normalize_context_period(
                entry,
                now=self.now,
                source_text="Start Friday instead",
                changed_fields={"starts_on"},
            )

    def test_recurring_note_edit_changes_weekday_rule(self):
        entry = self.entry("Work every Monday", "note", recurrence="weekly")
        entry.recurrence_days = [0]
        previous = ContextPeriod.of(entry)
        entry.content = "Work every Tuesday"
        normalize_context_period(entry, previous=previous, changed_fields={"content"})
        self.assertEqual(entry.recurrence_days, [1])

    def test_legacy_relative_content_requires_existing_anchor(self):
        entry = self.entry(
            "Dentist tomorrow", "note", starts_on=date(2026, 10, 14), ends_on=date(2026, 10, 14)
        )
        previous = ContextPeriod.of(entry)
        normalize_context_period(entry, previous=previous, now=self.now.replace(month=11))
        self.assertIn("2026-10-14", entry.content)
        unanchored = self.entry("Dentist tomorrow", "note")
        with self.assertRaises(ValueError):
            normalize_context_period(
                unanchored, previous=ContextPeriod.of(unanchored), now=self.now
            )

    def test_new_relative_period_does_not_reuse_old_start_or_accept_wrong_model_start(self):
        entry = self.entry(
            "No meetings 2026-10-14 through 2026-10-18",
            "preference",
            starts_on=date(2026, 10, 14),
            ends_on=date(2026, 10, 18),
        )
        previous = ContextPeriod.of(entry)
        entry.content = "No meetings for the next two weeks"
        normalize_context_period(entry, now=self.now, previous=previous, changed_fields={"content"})
        self.assertEqual((entry.starts_on, entry.ends_on), (date(2026, 10, 13), date(2026, 10, 26)))
        wrong = self.entry(
            "No meetings 2026-10-14 through 2026-10-27",
            "preference",
            starts_on=date(2026, 10, 14),
            ends_on=date(2026, 10, 27),
        )
        with self.assertRaises(ValueError):
            normalize_context_period(
                wrong, now=self.now, source_text="For the next two weeks, no meetings"
            )
