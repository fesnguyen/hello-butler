# Client Architecture

**Version:** 1.4  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

This document defines the Android architecture for Hello Butler, including local persistence, recording, asynchronous Butler delivery, synchronization, notifications, playback, background work, and instant startup.

---

# Core Mental Model

The Android client is the local execution engine for the user's day and the presentation layer for one persistent Butler conversation.

```text
                         BACKEND
                            │
             plans / reasons / returns results
                            │
                            ▼
                    authenticated APIs
                            │
                            ▼
                    LOCAL DATABASE
                            │
          ┌─────────────────┼─────────────────┐
          ▼                 ▼                 ▼
        UI STATE       DAILY EXECUTION    PENDING WORK
                            │
                     ┌──────┼──────┐
                     ▼      ▼      ▼
                   Alarm  Notify  Audio
```

> Read locally, write local UI state immediately, synchronize automatically, and use the backend only when backend intelligence or authoritative reconciliation is required.

---

# Responsibilities

The client owns:

- Compose UI and Daily Event presentation
- conversation presentation
- compressed audio recording
- temporary `Sending...` / `Sent` message state
- Room and local caches
- local Butler response-audio storage
- foreground result fetch
- WorkManager background result fetch
- Retrofit / OkHttp transport
- notifications and notification actions
- audio playback/routing
- alarms and scheduled local execution
- offline queues
- authentication session state
- instant-start behavior

The client does not own core Butler reasoning, semantic intent, planning, or authoritative server state.

---

# Technology Stack

```text
Language
└── Kotlin

UI
└── Jetpack Compose

State / Concurrency
├── ViewModel
├── Kotlin Coroutines
└── Flow

Persistence
├── Room
└── DataStore

Networking
├── Retrofit
├── OkHttp
└── kotlinx.serialization

Background / Scheduling
├── WorkManager
└── AlarmManager when exact execution is genuinely required

Device Services
├── microphone / compressed audio capture
├── Android audio playback/routing
└── Android notifications
```

Local Android STT is no longer the normal Order/Talk pipeline. Local TTS may remain as fallback/offline support where a prepared local event specifically needs it, but ordinary conversational responses use Butler response audio returned from the backend.

---

# Architectural Shape

```text
Compose UI
   ↓
ViewModel
   ↓
Repository
   ├── Room
   ├── Remote API
   ├── Audio cache
   └── Pending work
```

Do not introduce extra use-case/manager layers without a concrete need.

---

# Instant Startup

Opening the app must not block voice capture on synchronization.

```text
APP LAUNCH
    │
    ├── critical path
    │     ├── restore minimal local session/UI
    │     ├── render Main Screen shell
    │     └── enable hold-to-record immediately
    │
    └── background path
          ├── read Room
          ├── sync today's plan
          ├── refresh conversation
          ├── fetch pending results
          └── reconcile schedules
```

The app should feel immediately usable even while data is still loading.

---

# Butler Conversation

The Main Screen keeps the Daily Event list visible and overlays the Butler conversation above the persistent Order/Talk/Text controls.

Recording is intentionally lightweight:

```text
hold Order/Talk
      ↓
small recording animation in user's message area
      ↓
conversation history remains visible
```

Do not navigate to a dedicated recorder screen.

---

# Voice Request Lifecycle

```text
hold
 ↓
record compressed audio
 ↓
release
 ↓
create local outgoing placeholder = Sending...
 ↓
upload audio request
 ↓
server accepted / handling signal
 ↓
placeholder = Sent • time
 ↓
server completed FCM
 ↓
fetch canonical result
 ↓
replace placeholder with final transcript
 ↓
append Butler response text + audio reference
```

The user's own recorded audio does not need a normal playback control.

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
          GET canonical result + audio URL
                         │
                         ▼
                   Room / audio cache
```

Foreground and background paths must converge through the same repository/persistence logic.

---

# FCM Handling

FCM is a wake-up/completion signal, not the full response.

On completed Butler request:

1. start foreground coroutine/repository work if app process/screen is active;
2. otherwise enqueue WorkManager;
3. fetch the canonical result;
4. persist transcript and Butler message in Room;
5. start response-audio download immediately;
6. update active conversation UI or publish the notification.

---

# Local Conversation Persistence

Room stores the client-visible conversation needed for immediate rendering, including:

- message id / request id
- role
- text
- timestamp
- delivery state when temporary
- response-audio local path/status when relevant

Server text remains authoritative after fetch/reconciliation. Temporary Sending/Sent placeholders are device-side state only.

---

# Audio Cache

Butler response audio is cached/stored locally on the device so historical playback does not require repeated server download.

Audio download begins immediately after the completion result is known.

If a user presses an audio action before the download completes:

```text
user presses action
      ↓
observe current download
      ↓
wait briefly if needed
      ↓
play when available
```

Do not hide or disable normal playback actions merely because caching is still in progress unless playback cannot reasonably recover.

---

# Response Playback Actions

Use compact icons in the conversation UI:

```text
speaker icon → Speak Aloud
phone icon   → private / receive-as-call listening
```

Avoid ambiguous button text such as simply `Speak` or `Call` when the icon communicates the presentation mode more cleanly.

Private/call-style receive is a playback presentation, not a live call to the backend.

---

# Notifications

An ordinary Butler message notification shows the canonical response text and exposes:

```text
speaker icon
phone/private-listen icon
Open in App
```

`Open in App` is always retained for Butler message notifications.

The notification does not need to wait for response-audio caching to finish before showing playback actions. If selected early, playback waits for the active download.

The notification itself should not carry the audio binary.

---

# Morning Brief / Good Night Summary

Morning Brief and Good Night Summary are proactive-speech exceptions.

At their scheduled notification/execution time:

```text
content becomes due
      ↓
show/maintain notification surface
      ↓
automatically start Speak Aloud
      ↓
provide Stop immediately
      ↓
retain Open in App
```

The user can stop automatic playback at any time.

Ordinary Butler responses remain silent by default.

---

# Text Interaction

Text also records audio but returns editable prepared text.

```text
hold Text
  ↓
record
  ↓
upload
  ↓
backend prepares context-aware text
  ↓
client editor
```

Text mode should not use local STT as the authoritative normal pipeline.

---

# Offline Voice Capture

If connectivity is unavailable after recording:

```text
record locally
  ↓
queue pending audio request
  ↓
show appropriate pending state
  ↓
WorkManager retries when connected
```

Novel Butler reasoning still requires the backend.

---

# Direct Event Changes

Direct visible event editing remains local-first and bypasses AI:

```text
edit event
  ↓
Room transaction
  ↓
immediate UI update
  ↓
pending sync operation
  ↓
backend reconciliation
```

---

# WorkManager / AlarmManager

Use WorkManager for retryable connectivity-dependent work including:

- pending voice upload
- completed-result fetch
- response-audio download retry
- synchronization
- maintenance

Use AlarmManager only when genuinely exact local execution is required.

---

# Data Ownership

Backend authority includes server conversation text, User Context, Daily Plans/Events, Butler result text, and temporary server audio metadata.

Client ownership includes local playback audio, local device execution state, temporary Sending/Sent state, pending work, notification state, and UI preferences.

---

# Guiding Architecture

```text
                         USER
                           │
                           ▼
                    COMPOSE UI
                           │
                           ▼
                      VIEWMODEL
                           │
                           ▼
                     REPOSITORY
                           │
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
     ROOM            RETROFIT/OKHTTP       AUDIO CACHE
       ▲                   ▲                   ▲
       │                   │                   │
       └──────────────┬────┴────┬──────────────┘
                      │         │
                foreground   WorkManager
                      │         │
                      └────┬────┘
                           ▼
                         FCM
                           ▲
                           │
                        BACKEND
```

Keep the client simple enough that voice capture, background delivery, persistence, and playback remain easy to trace.
