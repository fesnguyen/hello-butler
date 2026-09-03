# Client Architecture

**Version:** 1.1  
**Status:** Initial  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

This document defines how the Android client is built.

It owns client-specific decisions such as technology stack, architectural components, local persistence, backend communication, synchronization, local event execution, speech, notifications, authentication state, and offline behavior.

Product behavior belongs in `PROJECT.md`. Global engineering rules belong in `ENGINEERING.md`. Detailed execution and data flows belong in `CLIENT_WORKFLOW.md`.

---

# Core Mental Model

The Android client is not a thin UI over the backend. It is the **local execution engine for the user's day**.

The backend reasons, prepares plans, generates Butler content, and maintains authoritative server state. The client stores synchronized state locally, presents it immediately, executes time-sensitive behavior on the device, accepts local changes, and synchronizes with the backend when connectivity is available.

```text
                         BACKEND
                            │
                 Plans / reasons / syncs
                            │
                            ▼
                    Synchronization
                            │
                            ▼
                    LOCAL DATABASE
                            │
          ┌─────────────────┼─────────────────┐
          ▼                 ▼                 ▼
        UI STATE       DAILY EXECUTION    SYNC QUEUE
                            │
                     ┌──────┼──────┐
                     ▼      ▼      ▼
                   Alarm  Notify  Speech
                            │
                            ▼
                           USER
```

> **Read locally, write locally first, synchronize automatically, and depend on the backend only when backend intelligence or authoritative reconciliation is required.**

---

# Client Responsibilities

The Android client owns:

- main UI and Daily Event presentation
- direct Daily Event editing
- local application state and persistence
- immediate local updates
- offline execution
- synchronization and pending queues
- Butler request delivery and response presentation
- local STT and TTS
- alarms, notifications, and event scheduling
- authentication session state
- connectivity awareness
- lightweight user preferences

The client does not own core Butler reasoning, nightly planning, semantic request interpretation, or authoritative server planning state.

---

# Technology Stack

```text
Language
└── Kotlin

UI
└── Jetpack Compose

State / Concurrency
├── Android ViewModel
├── Kotlin Coroutines
└── Kotlin Flow

Persistence
├── Room                  Local relational database
└── DataStore             Lightweight preferences

Networking
├── Retrofit              Backend API client
├── OkHttp                HTTP transport / streaming
└── kotlinx.serialization DTO serialization

Background / Scheduling
├── WorkManager           Deferred and retryable work
└── AlarmManager          Exact time-sensitive execution when required

Device Services
├── Android Speech Recognition / STT
├── Android Text-to-Speech / TTS
└── Android Notifications
```

Keep the stack small. Do not add architecture frameworks, RxJava, MVI frameworks, or extra abstraction layers without a concrete requirement. Dependency injection may be introduced when implementation complexity justifies it.

---

# Architectural Shape

The normal path is intentionally compact:

```text
UI
 ↓
ViewModel
 ↓
Repository
 ↓
Data / Device Services
```

Two major cross-cutting paths are:

```text
Room → Sync Engine → Backend

DailyEvent → Daily Execution → Alarm / Notification / TTS
```

A simple feature should not require unnecessary use-case, interactor, manager, gateway, and data-source layers.

---

# Client Components

```text
Android Client
│
├── UI
│   ├── Main Screen
│   ├── Event Components
│   ├── Butler Interaction
│   └── Response Presentation
│
├── ViewModel
│   ├── Main
│   └── Butler
│
├── Data
│   ├── Repositories
│   ├── Local Database
│   ├── Remote API
│   └── Preferences
│
├── Sync
│   ├── Sync Engine
│   └── Pending Operations
│
├── Execution
│   ├── Event Scheduler
│   ├── Alarm
│   └── Notification
│
├── Speech
│   ├── STT
│   └── TTS
│
├── Authentication
│
└── Core
    ├── Network
    ├── Connectivity
    └── Configuration
```

These are responsibilities, not a requirement for one class or directory per box.

---

# UI

Jetpack Compose owns presentation and direct user interaction: Main Screen, Daily Event list and editing, upcoming information, Order/Talk/Text interactions, response overlays, clarification, and loading/pending states.

```text
Room / Repository
       ↓
      Flow
       ↓
   ViewModel
       ↓
 Compose UI
```

The UI expresses user intent. It does not implement persistence versioning, sync queues, or backend reconciliation. Detailed visual decisions belong in `client/ui/`.

---

# ViewModel

ViewModels own screen-level state and orchestration.

They expose observable UI state, translate UI actions into repository operations, coordinate Butler interaction state, and expose loading, pending, error, and response states.

```text
User moves event → MainViewModel → DailyEventRepository

User presses Talk → ButlerViewModel → ButlerRepository → Backend
```

ViewModels should not contain SQL, HTTP implementation details, alarm APIs, or Butler reasoning.

---

# Repositories

Repositories are the primary boundary between ViewModels and data operations.

```text
ViewModel
    ↓
Repository
    ├── Room
    ├── Remote API
    └── Sync Queue
```

Initial repository responsibilities center on Daily Plans/Events, Butler interaction, authentication/session state, and synchronization where repository-level coordination is useful.

Avoid repositories for concepts that do not need a meaningful data boundary.

---

# Local Database

Room is the primary local persistence mechanism and the immediate source observed by the UI.

```text
Open screen → Room → Render immediately
                         │
                         └── Sync in parallel
```

When synchronization updates Room:

```text
Backend → Sync → Room → Flow → ViewModel → Compose
```

Room may initially persist Daily Plans, Daily Events, client-needed synchronized context, pending sync operations, pending Butler requests, and execution state that must survive process death.

Do not duplicate backend-only data unless the client needs it for presentation, execution, offline behavior, or synchronization.

---

# Immediate Local Writes

Direct user changes update local state first whenever safe.

```text
User edits event
      ↓
Repository
      ↓
Room updated
      ↓
UI updates immediately
      ↓
Sync operation queued
      ↓
Backend
```

This applies to operations such as complete, skip, delay, edit, create, cancel, and delete. Server reconciliation may later confirm or modify the local result.

---

# Remote API

The remote component communicates with:

```text
/api/auth/...
/api/butler/talk
/api/sync/...
```

It owns request/response DTOs, serialization, HTTP transport, authenticated headers, network error mapping, and streaming where required. It does not own UI presentation.

---

# Butler Interaction

All three modes use the same Butler backend capability.

```text
                 Main Screen
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
        Order       Talk       Text
          │          │          │
          └──────────┼──────────┘
                     ▼
              Butler Repository
                     │
                     ▼
             /api/butler/talk
```

The button describes the expected interaction experience, not semantic intent. The backend result determines what happened; the client determines how it is presented.

## Order

Optimized for requests where the user may leave immediately.

```text
Capture → Send → User may leave → Result
                              ↓
                    Sync state if changed
                              ↓
                 Overlay / notification
```

The screen need not remain active. Offline requests may be persisted for later delivery.

## Talk

Optimized for active, immediate interaction.

```text
Capture speech → Send → Immediate/streamed response → Present → TTS
```

Talk is latency-sensitive and may still produce domain changes.

## Text

Optimized for request accuracy.

```text
Speech → STT → Editable transcript → User review → Explicit send
```

After send, it follows normal Butler semantics and configured response presentation.

---

# Response Presentation

The backend determines what happened and what Butler should communicate. The client determines how the result is presented.

Possible presentations include:

- temporary overlay
- persistent visible result
- spoken response
- text response
- notification
- clarification prompt

Interaction mode, backend result, current app state, and user preferences may influence presentation.

---

# Synchronization

Synchronization is independent from Butler conversation. Direct client edits do not require AI.

```text
User change
    ↓
Room
    ↓
Pending operation
    ↓
Sync Engine
    ↓
Backend Sync API
    ↓
Reconciliation
    ↓
Room
```

The Sync Engine coordinates local/server convergence: push pending changes, receive server changes, apply server state, handle versions, retry temporary failures, and synchronize deletions/tombstones.

Detailed rules belong in `CLIENT_WORKFLOW.md`.

---

# Pending Operations

Operations that cannot reach the backend immediately must survive connectivity loss and process death when necessary.

A pending operation may identify its operation, target entity, local entity ID, expected/base version, requested changes, and retry state.

Exact fields belong in `CLIENT_WORKFLOW.md`.

This queue supports reliable offline-first synchronization; it is not an event-sourcing system.

---

# Offline Butler Requests

Offline daily execution and offline AI reasoning are different.

```text
Offline Butler request
       ↓
Persist pending request
       ↓
Connectivity returns
       ↓
Send to backend
       ↓
Receive result
       ↓
Apply synchronized changes
       ↓
Present result
```

Novel Butler reasoning requires the backend unless a local model is introduced later.

---

# Daily Execution

Daily execution is a first-class client responsibility.

> **The backend decides what should happen. The client makes prepared behavior happen locally at the appropriate time.**

```text
DailyEvent
    ↓
Event Scheduler
    ↓
Due time
    ↓
Execution behavior
    ├── notification
    ├── alarm
    ├── TTS
    └── no automatic action
```

---

# Event Scheduler

The scheduler translates synchronized Daily Events into local behavior using timing and configuration rather than event type alone.

```text
Morning Brief
start_time = 06:05
speak_aloud = true
→ local scheduled execution → TTS
```

```text
Meeting
start_time = 15:00
reminder = 15 minutes
speak_aloud = false
→ notification at 14:45
```

Prefer one flexible execution model. Do not create a separate manager for every event category without a concrete need.

---

# WorkManager and AlarmManager

Use **WorkManager** for deferrable, retryable, connectivity-dependent work that should survive process death, such as synchronization retry, pending Butler delivery, non-exact refresh, and maintenance.

Use **AlarmManager** only when genuinely time-sensitive local execution is required, such as wake-up, Morning Brief, or explicitly exact reminders.

Respect Android restrictions and permission requirements. Do not use exact alarms for work that can safely be deferred.

---

# Notifications

Notifications support non-intrusive local execution, including event reminders, meeting reminders, leave-now reminders, Butler results when the user has left the app, and clarification when appropriate.

Daytime notifications are silent by default unless configured otherwise.

Possible actions include:

```text
Dismiss
Done
Delay
Listen
```

Exact behavior belongs in workflow/UI documentation.

---

# Speech

## Speech-to-Text

STT converts speech to text for Order, Talk, and Text. Text mode exposes the transcript for review before send.

STT is an input mechanism and does not determine semantic intent.

## Text-to-Speech

TTS may speak Morning wake-up, Morning Brief, Talk responses, optional confirmations, configured reminders, and Good Night Summary.

Speech should remain intentional rather than accompany every notification.

---

# Authentication

The client supports two account entry paths:

```text
Email + password
├── Register
└── Login

Google
└── Sign in with Google
```

For Google sign-in, Android obtains the Google credential using the supported
Google identity flow and sends the resulting ID token to the Butler backend. The
backend, not the client, establishes the Butler user identity.

Successful authentication returns a short-lived access token and rotating
refresh token.

```text
Login / Google sign-in
        ↓
access token + refresh token
        ↓
secure local session storage
        ↓
Authorization: Bearer <access token>
        ↓
Butler API / Sync API
```

Authentication secrets must not be stored in Room or ordinary DataStore. Store
refresh tokens and other long-lived session secrets using Android-protected
credential storage backed by the platform keystore where practical. Access
tokens may be kept in memory and replaced through refresh.

When an authenticated API request encounters an expired access token, the
network/session layer performs one coordinated refresh and retries the request.
Concurrent requests must not independently rotate the same refresh token.

Logout asks the backend to revoke the current refresh session, then removes local
session credentials.

Normal local execution should not require an online authentication round-trip. If
today's plan is synchronized and connectivity disappears, local execution
continues. Network work that requires authentication waits until connectivity and
a valid session are available.

The client does not implement roles or a general authorization system and does
not treat a locally stored `user_id` as authority over backend data.

---

# Preferences

DataStore stores lightweight client configuration such as speech behavior, automatic playback, notification preferences, overlay duration, response presentation, and UI preferences.

Do not use DataStore as a replacement for relational product data.

---

# Connectivity

Connectivity awareness guides immediate versus queued network work.

```text
Online  → send / synchronize normally
Offline → execute locally → queue network work → retry later
```

Network calls must still handle real failures; reported connectivity does not guarantee backend reachability.

---

# Offline Capability Boundary

Offline execution normally supports:

- viewing synchronized plans/events
- direct event editing
- completion and skipping
- local event creation/deletion
- alarms and notifications
- prepared speech
- Morning Brief
- Good Night Summary
- pending sync operations
- pending Butler requests

Offline mode does not provide novel server-side AI reasoning.

---

# Data Ownership

Backend authority after synchronization includes authenticated identity, User Context, server conversation history, Daily Plans, Daily Events, generated Butler content, and server versions.

The client owns immediate local state before synchronization, device execution state, local scheduling, pending operations, pending Butler requests, temporary UI state, and client preferences.

Room is the client's immediate working source of synchronized product state. Synchronization reconciles it with backend authority.

---

# Initial Package Direction

```text
app/
├── ui/
├── data/
│   ├── local/
│   ├── remote/
│   └── repository/
├── sync/
├── execution/
├── speech/
├── auth/
└── core/
```

ViewModels may live close to their screens/features when that improves locality.

Do not create empty packages merely to match this document. The structure evolves as code is implemented.

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
                     REPOSITORIES
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
        ROOM          BUTLER API        SYNC ENGINE
          │                │                │
          │                └──────┬─────────┘
          │                       ▼
          │                    BACKEND
          │
          ├──────────────────────────────────┐
          ▼                                  ▼
   DAILY EXECUTION                    UI STATE / FLOWS
          │
     ┌────┼────┐
     ▼    ▼    ▼
   Alarm Notify TTS
          │
          ▼
        USER
```

Keep the client small enough that important actions remain easy to trace from UI to local state, device execution, and synchronization.

The client exists to make the prepared day reliable, immediate, and usable even when the backend is not currently reachable.
