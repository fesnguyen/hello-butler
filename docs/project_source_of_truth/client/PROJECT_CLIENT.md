# PROJECT_CLIENT — Android Architecture and Workflow

**Status:** Source of Truth · **Authority:** [PROJECT.md](../PROJECT.md) → [ENGINEERING.md](../ENGINEERING.md) → this subsystem document → feature documents.

## Scope and feature index

This is the Android client's primary technical entry point, combining its former architecture and workflow documents. Consult the feature documents only for the area being changed:

| Feature | Detailed source of truth |
| --- | --- |
| Configurable audio, playback, volume, delays, reminder speech | [AUDIO_WORKFLOWS.md](AUDIO_WORKFLOWS.md) |
| Home-screen widget | [HOME_SCREEN_WIDGET.md](HOME_SCREEN_WIDGET.md) |
| Reconciliation and offline synchronization | [CLIENT_SYNC_FLOW.md](CLIENT_SYNC_FLOW.md) |

Product authority remains [PROJECT.md](../PROJECT.md); shared engineering rules remain [ENGINEERING.md](../ENGINEERING.md). Feature documents may elaborate on this parent, but cannot override it silently.

## Architecture

**Version:** 1.9
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

### Purpose

This document defines Android architecture for the single Main Screen, Butler conversation overlay, local persistence, Order/Talk recording, typed Text input, asynchronous delivery, notifications, playback, background work, and instant startup.

---

### Core Mental Model

The Android client is the local execution engine for the user's day and the presentation layer for one persistent Butler conversation.

```text
Main Screen
├── Daily Plan / Daily Event list
├── prominent Upcoming divider
├── Upcoming Events derived from User Context
├── Butler conversation overlay
└── persistent controls
    ├── Order → audio
    ├── Talk  → audio
    └── Text  → typed composer
```

There is no separate conversation screen. `Open in App` opens the Main Screen with the conversation overlay visible, normally around 60–70% of screen height as appropriate to the current UI.

#### Daily Plan and Upcoming presentation

The Main Screen presents two clearly distinct sections:

```text
DAILY PLAN
  Daily Event
  Daily Event

════════ UPCOMING ════════

  Upcoming Event
  Upcoming Event
```

Daily Events are concrete members of the Daily Plan. Upcoming Events are active/future projections derived from actionable User Context. The client must not require an `UpcomingEvent` persistence table or treat Upcoming Events as another Daily Event collection.

The Upcoming section sits below the Daily Plan behind a visually strong divider/header. Expired Upcoming items are not displayed. Daily routines are not displayed as Upcoming Events.

Upcoming items expose appropriate modify/reschedule/skip/remove interactions like Daily Events, but these actions target the underlying User Context/occurrence semantics through the backend. A recurring item may therefore be changed for one occurrence or for the recurring rule.


---

### Responsibilities

The client also exposes an optional Android home-screen widget. The widget is a compact Butler interaction surface, not a miniature Daily Plan. It shows the latest locally persisted Butler response and reuses the same conversation, recording, notes, response-audio, and navigation paths as the main application. Detailed behavior is defined in `HOME_SCREEN_WIDGET.md`.

The client owns:

- Compose UI and Daily Event presentation
- conversation overlay/presentation
- compressed audio recording for Order/Talk
- direct typed composer for Text
- temporary delivery state
- Room and local caches
- local Butler response-audio storage
- foreground result fetch
- WorkManager background result fetch
- Retrofit / OkHttp transport
- notifications/actions
- audio playback/routing
- alarms/local execution
- offline queues
- authentication session state
- instant-start behavior

The client does not own Butler reasoning, semantic intent, planning, speech transcription, TTS provider selection, or authoritative server state.

---

### Technology Stack

```text
Kotlin + Jetpack Compose
ViewModel + Coroutines + Flow
Room + DataStore
Retrofit + OkHttp + kotlinx.serialization
WorkManager + AlarmManager when exact execution is required
Android microphone recording + audio playback/routing + notifications
```

Local Android STT is not part of the normal Butler architecture. Order/Talk send recorded audio to the backend. Text sends typed text directly. Android native TTS is not the Butler voice path. Backend-selected OpenAI or open-source TTS provides Butler speech.

---

### Architectural Shape

```text
Compose UI
   ↓
ViewModel
   ↓
Repository
   ├── Room
   ├── Remote APIs
   ├── Audio cache
   └── Pending work
```

Do not add extra layers without a concrete need.

---

### Instant Startup

```text
APP LAUNCH
    │
    ├── critical path
    │     ├── restore minimal local session/UI
    │     ├── render Main Screen shell
    │     ├── enable Order/Talk recording immediately
    │     └── make Text composer available immediately
    │
    └── background path
          ├── read/reconcile Room
          ├── sync today's plan
          ├── refresh conversation
          ├── fetch pending results
          └── reconcile schedules
```

Interaction must not wait for network synchronization.

---

### Order / Talk Audio Lifecycle

```text
hold Order/Talk
 ↓
small recording animation inside conversation
 ↓
release
 ↓
local placeholder = Sending...
 ↓
upload compressed audio
 ↓
accepted / handling
 ↓
placeholder = Sent • time
 ↓
FCM completed
 ↓
fetch canonical result
 ↓
replace placeholder with transcript
 ↓
append Butler response text immediately
 ↓
reconcile/download response audio when ready
```

The user's own audio has no normal playback control.

---

### Text Lifecycle

```text
tap Text
 ↓
composer enabled in existing conversation overlay
 ↓
type/edit message
 ↓
Send
 ↓
insert exact typed message locally + Sending state
 ↓
POST text request
 ↓
accepted → Sent
 ↓
FCM completed
 ↓
fetch canonical result
 ↓
append Butler response text immediately
 ↓
reconcile/download response audio when ready
```

Text does not record audio and does not wait for STT or a backend-generated draft before the user can edit/send.

Audio and Text use different API ingress methods but converge on the same request/result repository model.

---

### Foreground / Background Completion

```text
                    FCM completed
                         │
            ┌────────────┴────────────┐
            │                         │
       APP FOREGROUND            APP BACKGROUND
            │                         │
 Coroutine / Repository           WorkManager
            │                         │
            └────────────┬────────────┘
                         ▼
                 Retrofit / OkHttp
                         │
                         ▼
                GET canonical result
                         │
                         ▼
                   Room / audio cache
```

Both paths must use the same repository/persistence logic.

---

### Local Conversation Persistence

Room stores message/request identity, role, text, timestamp, temporary delivery state, input source when useful, and Butler response-audio local path/cache status.

For audio requests, final transcript replaces Sending/Sent placeholder content. For text requests, the submitted text never needs transcript replacement; only delivery/result state changes.

---

### Response Audio

Every normal Butler response may include backend-generated response audio regardless of whether the user input was audio or text.

Canonical text is shown as soon as the completed result is received. Audio may still be pending; when the backend reports it ready, the client downloads/caches it and enables playback. The client must not delay text while waiting for TTS or audio download.

Conversation messages keep the UI compact:

```text
Butler · 0:08
<response text>
[Listen Aloud] [Phone Listen]
```

Playback controls stay directly under the response rather than occupying large separate UI areas.

---

### Notifications

Ordinary Butler notification:

```text
<canonical Butler response text>
[speaker icon] [private-listen icon] [Open in App]
```

The notification does not carry audio bytes. If playback is selected before audio is ready/cached, reconcile/download it first.

`Open in App` opens the Main Screen with the Butler conversation overlay visible; it does not navigate to a dedicated chat screen.

---

### Morning Brief / Good Night Summary

These are proactive-speech exceptions. Their audio is generated by the same backend TTS service and user TTS method as normal Butler speech, then downloaded/cached by the client. At due time, execute the configured audio sequence when the persisted daily briefing preference allows automatic speech; provide Stop immediately and retain Open in App. Do not use Android native `TextToSpeech`/`LocalTextToSpeech` for Butler speech.

---

### Offline Work

Order/Talk recordings may be queued locally for upload when connectivity returns. Typed requests may also be queued when needed. WorkManager owns retryable connectivity-dependent work such as pending uploads, result fetch, audio readiness/download retry, and synchronization. It does not synthesize Butler speech.

Direct event edits remain local-first and bypass AI.

---

### Data Ownership

Backend authority includes server conversation text, User Context, Daily Plans/Events, request/result text, speech transcription, TTS selection, and temporary server audio metadata.

Client ownership includes local recording before upload, typed composer state, temporary delivery state, Room/cache, local response audio, playback routing, notification state, pending work, and device execution.

---

### User Settings, Credits, TTS, and Preferences

The previous Profile menu/surface is renamed **User Settings**. Application/account settings and user-manageable saved Butler preferences live there; do not keep a duplicate Profile/Settings destination.

```text
Profile
├── existing account/profile information
├── Credits        read-only
└── TTS Method     editable
    ├── Open Source
    └── OpenAI
```

User Settings displays **Notes & Preferences** from the backend's user-manageable User Context projection. Each row has description text and an Is preference switch; Add, edit, and confirmed delete mutate the same backend records. Account/TTS Save does not submit or replace this list.

`UserSettingsRepository` reconciles stable IDs into the Room `saved_context` cache (database version 5, additive 4→5 migration). The screen observes Room. Structured `user_context` changes trigger reconciliation after conversation text is persisted; the existing `DailySyncWorker` also reconciles at startup and periodically. Full snapshots remove absent/deleted records. Logout clears the cache. Mutations require connectivity, retain the editor on failure, and use the same UUID on retry; this cache is not another authoritative persistence model.

The client fetches and displays the backend-authoritative credit balance. It must never calculate, deduct, or directly modify credits. The TTS method is editable and persisted through the profile API.

The UI should explain that Open Source speech does not add TTS credit cost while OpenAI speech consumes additional credits. A zero balance does not prevent selecting OpenAI and does not rewrite the preference: the backend resolves OpenAI to open-source TTS at runtime while credits are unavailable.

Response playback remains based on backend-generated audio. Android native TTS is not the fallback for Butler speech; if backend TTS is unavailable, canonical response text remains usable.

---

### Guiding Architecture

```text
                    COMPOSE UI
                        │
          ┌─────────────┴─────────────┐
          │                           │
   Order/Talk audio               Text input
          │                           │
          └─────────────┬─────────────┘
                        ↓
                    Repository
                        │
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
      Room        Retrofit/OkHttp    Audio cache
                        ▲
              foreground / WorkManager
                        ▲
                       FCM
```


### Configurable Audio Experience

`execution/audio/AudioWorkflow.kt` centralizes strict `PLAY`/`DELAY` definitions and group membership. `AudioSequence` owns sequence order and completion checkpoints. `AudioWorkflowStore` persists execution journals and independent group shuffle queues in device-local SharedPreferences; Room/server content stays authoritative and no tables are added. `SoundVoiceSettings` persists volume and the three automatic-speech preferences with a Flow observed by settings and playback.

`ButlerAudioPlaybackService` owns the sole MediaPlayer for conversation, scheduled speech, and preview. The old `SpeechForegroundService` is only a compatibility entry point for existing intents. Both outward/private listening use this same player. Gain applies through `MediaPlayer.setVolume`, never Android stream-volume APIs. Automatic requests defer around active playback; manual requests stop the active sequence. Preview does not interrupt existing speech.

`AudioWorkflowScheduler` persists delays through uniquely named/tagged WorkManager work. An existing exact-alarm permission also allows the delayed automatic foreground-service start. Without an allowed start, the worker offers Listen. No player, audio focus, or coroutine remains alive during a delay. Journal checkpoints suppress repeated segments and duplicate notifications; a crash during audible output cancels the ambiguous execution on restart rather than automatically repeating it.

Reminders retain ordinary notifications even when automatic speech is disabled. A reminder Listen/automatic request prepares speech on demand through the existing backend shared TTS service, then uses the existing event audio download/cache path. Eligible conversation completion uses foreground/high-priority FCM playback where permitted, with audio-worker reconciliation and Listen fallback otherwise. See `AUDIO_WORKFLOWS.md` for configuration, state transitions, and verification.

---

## UI and execution workflows

**Version:** 2.0
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `PROJECT_CLIENT.md`

---

### Main Screen

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

### Conversation Overlay

Conversation remains visible while the user interacts. Messages stay compact.

A Butler message renders canonical text immediately. Response audio may still be preparing.

#### Playback controls

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

### Order / Talk / Text

Order and Talk use press-and-hold recording:

```text
press and hold → record → release → Sending… → Sent • <time> → reconcile transcript/result
```

No extra End button or historical user-audio playback is required. Conversation remains readable while recording.

Text uses direct typed composition and no STT. The submitted text appears immediately and follows the same asynchronous result lifecycle as Order/Talk.

---

### Completion and Sync

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

### Morning Brief / Good Night Summary

Both use the shared backend TTS service and the user's TTS method. When due, they may auto-start Listen Aloud and must expose Stop immediately. Do not use Android native TTS as a Butler speech fallback.

Ordinary Butler messages remain silent by default; Speak Butler responses aloud opts into automatic playback of recent completed responses. Manual listening remains available independently.

---

### Events

Direct Daily Event edits remain deterministic/local-first:

```text
Room → immediate UI → pending sync → backend reconciliation → Room
```

Upcoming Event modify/reschedule/skip/remove actions target their source User Context. Do not create a separate authoritative Upcoming Event record. Recurring context must distinguish occurrence-level changes from rule-level changes.

---

### User Settings

The destination is **User Settings**, containing account information, credits, TTS selection, and **Notes & Preferences**.

Credits are backend-authoritative and read-only on the client. TTS method is editable through the existing profile/settings contract. Android does not calculate/deduct credits.

#### Notes & Preferences

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

#### Preference synchronization

A preference remembered through conversation must appear in User Settings without manual refresh. Likewise, direct User Settings mutations must affect the context Butler subsequently receives.

```text
conversation mutation ─┐
                      ├→ backend User Context → repository sync → Room → User Settings
settings mutation ─────┘
```

Use the normal repository/Room reconciliation pattern and idempotent upserts. Trigger/queue reconciliation after relevant successful Butler mutations and during normal startup/background sync so the list converges even after process/network interruption.

---

### Startup and Offline

Render a usable Main Screen and enable Order/Talk/Text before full synchronization completes. Background work loads Room-backed data, syncs plans/context/conversation, fetches pending responses, and reconciles audio.

Pending audio/text uploads and direct mutations may be queued for WorkManager retry. Correctness must not depend on manual Refresh.

---

### Guiding UI Principle

Butler should feel continuously present without becoming visually heavy. Keep controls comfortably tappable, keep canonical text available before optional speech, and expose one coherent saved-context experience instead of separate disconnected preference/note stores.


### Sound & Voice and Audio Sequences

Sound & Voice is in User Settings. Volume is horizontal, 0–100%, initially 70%, saved immediately. Adjusting starts one bundled sample and changes the current player's gain without restarting that sample for each slider movement. Finishing adjustment/leaving settings stops preview. The original `morning_warmup_0.mp3` is copied to `volume_preview.mp3`; missing/failed optional samples are skipped with no TTS call.

Automatic preferences are device-local and persisted: reminders default off, ordinary responses default off, daily briefings default on. Workers/service read the same values. Disabling automatic speech preserves text, ordinary notifications, and explicit manual listening.

Morning Brief: long opening → shuffled morning warm-up → persist continuation at warm-up completion + 300 seconds → release service/player → short opening → existing prepared speech. Good Night: long opening → shuffled evening warm-up → existing prepared speech. Reminder: ordinary notification, plus optional short opening → shared backend reminder speech. Response: existing response audio only. A manual listen suppresses a pending automatic repeat of that response.

Waiting continuations expose a Stop notification. Stop cancels the journal and tagged workers/exact alarm. A delayed worker cannot assume a background service-start exemption: offer Listen when required. App restart repairs persisted waiting work; in-progress ambiguous audio is cancelled to avoid repeated announcements. Definition snapshots keep an already-scheduled sequence stable across configuration edits. See `AUDIO_WORKFLOWS.md`.
