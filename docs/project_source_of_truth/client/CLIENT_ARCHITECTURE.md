# Client Architecture

**Version:** 1.5  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

This document defines Android architecture for the single Main Screen, Butler conversation overlay, local persistence, Order/Talk recording, typed Text input, asynchronous delivery, notifications, playback, background work, and instant startup.

---

# Core Mental Model

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

## Daily Plan and Upcoming presentation

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

# Responsibilities

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

The client does not own Butler reasoning, semantic intent, planning, speech transcription, or authoritative server state.

---

# Technology Stack

```text
Kotlin + Jetpack Compose
ViewModel + Coroutines + Flow
Room + DataStore
Retrofit + OkHttp + kotlinx.serialization
WorkManager + AlarmManager when exact execution is required
Android microphone recording + audio playback/routing + notifications
```

Local Android STT is not part of the normal Butler architecture. Order/Talk send recorded audio to the backend. Text sends typed text directly. Local TTS may remain only where an intentional offline/prepared fallback requires it.

---

# Architectural Shape

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

# Instant Startup

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

# Order / Talk Audio Lifecycle

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
append Butler response text + audio reference
```

The user's own audio has no normal playback control.

---

# Text Lifecycle

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
append Butler response text + audio reference
```

Text does not record audio and does not wait for STT or a backend-generated draft before the user can edit/send.

Audio and Text use different API ingress methods but converge on the same request/result repository model.

---

# Foreground / Background Completion

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

# Local Conversation Persistence

Room stores message/request identity, role, text, timestamp, temporary delivery state, input source when useful, and Butler response-audio local path/cache status.

For audio requests, final transcript replaces Sending/Sent placeholder content. For text requests, the submitted text never needs transcript replacement; only delivery/result state changes.

---

# Response Audio

Every normal Butler response may include backend-generated response audio regardless of whether the user input was audio or text.

Audio download starts immediately after completion result retrieval. Conversation messages keep the UI compact:

```text
Butler · 0:08
<response text>
[speaker icon] [phone/private-listen icon]
```

Playback controls stay directly under the response rather than occupying large separate UI areas.

---

# Notifications

Ordinary Butler notification:

```text
<canonical Butler response text>
[speaker icon] [private-listen icon] [Open in App]
```

The notification does not carry audio bytes. If playback is selected while caching is still running, wait for the active download and then play.

`Open in App` opens the Main Screen with the Butler conversation overlay visible; it does not navigate to a dedicated chat screen.

---

# Morning Brief / Good Night Summary

These are proactive-speech exceptions. At due time, automatically start Speak Aloud, provide Stop immediately, and retain Open in App. Ordinary Butler responses remain silent by default.

---

# Offline Work

Order/Talk recordings may be queued locally for upload when connectivity returns. Typed requests may also be queued when needed. WorkManager owns retryable connectivity-dependent work such as pending uploads, result fetch, audio download retry, and synchronization.

Direct event edits remain local-first and bypass AI.

---

# Data Ownership

Backend authority includes server conversation text, User Context, Daily Plans/Events, request/result text, speech transcription, and temporary server audio metadata.

Client ownership includes local recording before upload, typed composer state, temporary delivery state, Room/cache, local response audio, playback routing, notification state, pending work, and device execution.

---

# Guiding Architecture

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
