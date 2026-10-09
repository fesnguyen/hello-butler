# Client Workflow

**Version:** 2.0
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `CLIENT_ARCHITECTURE.md`

---

# Main Screen

Hello Butler has one primary product screen:

```text
MainScreen
├── DailyEventList
├── UpcomingDivider
├── UpcomingEventList
├── ButlerConversationOverlay
└── ButlerControlBar
    ├── Order
    ├── Talk
    └── Text
```

Butler interaction happens over the user's day; there is no separate conversation screen. Upcoming Events appear below Daily Plan and are derived from active/future User Context. They are planning input, not persisted future Daily Plans, and ordinary routines do not appear in Upcoming.

`Open in App` from a notification opens Main Screen with the Butler conversation overlay visible.

The optional home-screen widget provides a compact path into the same Butler experience. It shows the latest Butler response and exposes Open Butler, Talk, Take Note, Listen Aloud, and As a Call actions. It does not duplicate the Daily Plan or create a second conversation/data model. See `HOME_SCREEN_WIDGET.md` for the widget-specific workflow.

---

# Conversation Overlay

Conversation remains visible while the user interacts. Messages stay compact.

A Butler message renders canonical text immediately. Response audio may still be preparing.

## Playback controls

Under a Butler response, show two compact horizontal controls rather than tiny bare icons:

```text
[ 🔊 Listen Aloud ]   [ ☎ Phone Listen ]
```

They should have comfortable touch targets while remaining visually subordinate to the message.

Both controls use the same response audio and have three user-visible states:

```text
Loading → Ready → Speaking / Stop ■
```

- **Loading:** speech is pending/downloading/caching. Show progress and prevent duplicate playback requests.
- **Ready:** show the normal route icon/action.
- **Speaking:** replace the active route's normal action with a square Stop action; Stop ends playback immediately.

Speaker uses the normal outward route. Phone Listen uses private/call-style routing; it is not a realtime/network call. Only one route plays at a time. Selecting the other route while speaking stops/switches the current playback rather than overlapping it.

---

# Order / Talk / Text

Order and Talk use press-and-hold recording:

```text
press and hold → record → release → Sending… → Sent • <time> → reconcile transcript/result
```

No extra End button or historical user-audio playback is required. Conversation remains readable while recording.

Text uses direct typed composition and no STT. The submitted text appears immediately and follows the same asynchronous result lifecycle as Order/Talk.

---

# Completion and Sync

Whether input was audio or text, completion produces canonical text first:

```text
FCM completed(request_id)
      ↓
foreground repository OR background WorkManager
      ↓
GET canonical result
      ↓
Room persistence
      ↓
show text immediately
      ↓
reconcile/download audio independently
```

FCM is a wake-up signal, not canonical content. Request completion does not wait for TTS. Foreground/background execution must converge on the same Room state and must not duplicate conversation messages.

Ordinary notifications show the same Butler text plus playback actions and `Open in App`. Playback can enter Loading while speech is not ready.

---

# Morning Brief / Good Night Summary

Both use the shared backend TTS service and the user's TTS method. When due, they may auto-start Listen Aloud and must expose Stop immediately. Do not use Android native TTS as a Butler speech fallback.

Ordinary Butler messages remain silent by default; Speak Butler responses aloud opts into automatic playback of recent completed responses. Manual listening remains available independently.

---

# Events

Direct Daily Event edits remain deterministic/local-first:

```text
Room → immediate UI → pending sync → backend reconciliation → Room
```

Upcoming Event modify/reschedule/skip/remove actions target their source User Context. Do not create a separate authoritative Upcoming Event record. Recurring context must distinguish occurrence-level changes from rule-level changes.

---

# User Settings

The destination is **User Settings**, containing account information, credits, TTS selection, and **Notes & Preferences**.

Credits are backend-authoritative and read-only on the client. TTS method is editable through the existing profile/settings contract. Android does not calculate/deduct credits.

## Notes & Preferences

Use one user-manageable list backed by the existing authoritative User Context/preference persistence:

```text
Notes & Preferences                         [ + Add ]

Preference
I love going to the beach when I have a day off.

Note
Remember to ask John about the old laptop.
```

A saved item has required description/text and an **Is preference** switch. No title is required in the current design.

Adding/editing opens a compact editor with:

```text
Description
[................................]

Is preference   [ on/off ]

[Save]
```

A preference may influence Butler personalization/planning when relevant. An ordinary note remains saved/retrievable but is not automatically treated as a personalization preference.

Users can add, edit, delete, and change the preference status of an item. These actions mutate backend-authoritative User Context; do not maintain a separate client-only notes/preferences store. Only user-manageable notes/preferences are listed—exclude routines, temporary/one-time planning context, Upcoming Events, and internal metadata.

## Preference synchronization

A preference remembered through conversation must appear in User Settings without manual refresh. Likewise, direct User Settings mutations must affect the context Butler subsequently receives.

```text
conversation mutation ─┐
                      ├→ backend User Context → repository sync → Room → User Settings
settings mutation ─────┘
```

Use the normal repository/Room reconciliation pattern and idempotent upserts. Trigger/queue reconciliation after relevant successful Butler mutations and during normal startup/background sync so the list converges even after process/network interruption.

---

# Startup and Offline

Render a usable Main Screen and enable Order/Talk/Text before full synchronization completes. Background work loads Room-backed data, syncs plans/context/conversation, fetches pending responses, and reconciles audio.

Pending audio/text uploads and direct mutations may be queued for WorkManager retry. Correctness must not depend on manual Refresh.

---

# Guiding UI Principle

Butler should feel continuously present without becoming visually heavy. Keep controls comfortably tappable, keep canonical text available before optional speech, and expose one coherent saved-context experience instead of separate disconnected preference/note stores.


# Sound & Voice and Audio Sequences

Sound & Voice is in User Settings. Volume is horizontal, 0–100%, initially 70%, saved immediately. Adjusting starts one bundled sample and changes the current player's gain without restarting that sample for each slider movement. Finishing adjustment/leaving settings stops preview. The original `morning_warmup_0.mp3` is copied to `volume_preview.mp3`; missing/failed optional samples are skipped with no TTS call.

Automatic preferences are device-local and persisted: reminders default off, ordinary responses default off, daily briefings default on. Workers/service read the same values. Disabling automatic speech preserves text, ordinary notifications, and explicit manual listening.

Morning Brief: long opening → shuffled morning warm-up → persist continuation at warm-up completion + 300 seconds → release service/player → short opening → existing prepared speech. Good Night: long opening → shuffled evening warm-up → existing prepared speech. Reminder: ordinary notification, plus optional short opening → shared backend reminder speech. Response: existing response audio only. A manual listen suppresses a pending automatic repeat of that response.

Waiting continuations expose a Stop notification. Stop cancels the journal and tagged workers/exact alarm. A delayed worker cannot assume a background service-start exemption: offer Listen when required. App restart repairs persisted waiting work; in-progress ambiguous audio is cancelled to avoid repeated announcements. Definition snapshots keep an already-scheduled sequence stable across configuration edits. See `AUDIO_WORKFLOWS.md`.
