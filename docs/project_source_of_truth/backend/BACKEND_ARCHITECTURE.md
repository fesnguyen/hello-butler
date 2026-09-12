# Backend Architecture

**Version:** 1.4  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

This document defines the backend architecture for Hello Butler.

The backend owns Butler intelligence, authoritative server state, asynchronous Butler request processing, conversation persistence, response audio generation, authentication, planning, and synchronization.

---

# Architectural Pattern

```text
API
 ↓
Application
 ↓
Domain
 ↑
Infrastructure
```

API owns HTTP/authentication boundaries. Application owns orchestration and transaction boundaries. Domain owns product concepts/rules. Infrastructure owns PostgreSQL, AI providers, push, storage, and other integrations.

---

# Technology Direction

```text
Runtime / Tooling
├── Python 3.12+
├── uv
├── Ruff
└── Pyright

Backend
├── FastAPI
├── Pydantic v2
├── SQLAlchemy 2.x Async
└── Alembic

Database
└── PostgreSQL 16+

AI
├── LangGraph where useful
└── OpenAI multimodal/audio-capable APIs

Infrastructure
├── Docker / Compose
├── FCM push integration
└── temporary response-audio storage
```

Do not add infrastructure until a concrete requirement justifies it.

---

# Primary API Boundaries

Conceptually:

```text
/api/auth/...
/api/butler/requests
/api/butler/requests/{request_id}
/api/sync/...
/api/planning/...
/api/push/device
```

`/api/butler/requests` is the asynchronous Butler request boundary for recorded audio.

A request should be accepted quickly and return a stable request identifier rather than keeping the client connected while AI processing finishes.

Conceptual request:

```text
POST /api/butler/requests
Content-Type: multipart/form-data

interaction_mode = order | talk | text
audio = compressed recording
```

Conceptual acceptance:

```text
202 Accepted
request_id = <stable id>
```

Exact route/DTO naming may evolve, but the asynchronous contract should remain.

---

# Recorded Audio Request Architecture

```text
Android
  ↓ compressed audio
POST /api/butler/requests
  ↓
create Butler request
  ↓
return 202 + request_id
  ↓
continue processing asynchronously
  ├── speech understanding / transcription
  ├── context loading
  ├── Butler reasoning
  ├── tools / domain actions
  ├── response text
  └── response audio
  ↓
save result
  ↓
FCM completed
```

The backend must not require a persistent live voice/WebRTC session for the normal Butler interaction.

---

# Request Lifecycle

A request has a durable server identity and clear lifecycle. Initial conceptual states:

```text
accepted
processing
completed
failed
```

The client may map these to simpler visible states such as `Sending...` and `Sent`.

The backend should publish a lightweight handling/accepted signal when useful so the client can transition from `Sending...` to `Sent` without waiting for full AI completion.

---

# Interaction Semantics

## Order

The user expects Butler to handle the request and may leave immediately.

## Talk

The user expects a conversational answer but processing is still asynchronous at the transport level. Talk does not require a live media connection.

## Text

The backend receives audio, then uses speech, user preference, and relevant conversation context to prepare editable text. Text mode does not automatically execute the prepared content as an Order/Talk action.

Interaction mode describes user expectation; semantic intent still comes from the actual request.

---

# Butler Result Contract

A completed Order/Talk request conceptually produces:

```text
request_id
user_transcript
Butler response text
Butler response audio asset/reference
changed entity metadata when relevant
completion timestamp
```

A completed Text request produces editable prepared text and any metadata needed by the client editor.

The canonical conversation result is text plus associated Butler audio. The notification is not a second result.

---

# Response Audio

The backend generates response audio after response text is finalized.

Response audio is retained only for a limited period. Retention duration is configuration and may evolve with cost/privacy needs.

User input audio is temporary processing data unless a future product requirement explicitly justifies longer retention.

The backend must not promise permanent historical audio restoration.

---

# Push Boundary

FCM is a wake-up signal, not the canonical payload.

Conceptually:

```text
request completed
   ↓
FCM
   ├── type = butler_request_completed
   └── request_id
```

The client then fetches the canonical result over authenticated HTTP.

The push payload should remain small and must not transport the response audio file.

Routine Daily Plan synchronization may continue using separate silent push hints such as `daily_plan_changed`.

---

# Result Fetch

Conceptually:

```text
GET /api/butler/requests/{request_id}
        ↓
response text
user transcript
response audio URL/reference
semantic/domain result metadata
```

The audio URL/reference should be suitable for authenticated or short-lived download according to the final storage implementation.

---

# Conversation Persistence

The backend persists durable text conversation history:

```text
user transcript
Butler response text
roles + timestamps
```

Audio is not the permanent server conversation record.

For Order/Talk, final persistence should produce one user conversation message and one Butler conversation message for the completed interaction.

Temporary client-visible `Sending...` / `Sent` placeholders are client state, not conversation-history content.

---

# Domain Actions

AI may decide what the user intends, but deterministic application/domain code validates and applies important mutations.

Examples:

```text
create_daily_event
update_daily_event
skip_daily_event
remember_user_context
answer_today_events
replan_today
```

Do not grant the AI provider direct database ownership.

---

# Planning and Daily Lifecycle

The existing Daily Plan / Daily Event lifecycle remains authoritative:

```text
User Context
  ↓
plan tomorrow
  ↓
DailyPlan + DailyEvents
  ↓
Morning Brief
  ↓
day execution / replanning
  ↓
Good Night Summary
  ↓
prepare tomorrow
```

Morning Brief and Good Night Summary are generated as Butler content and delivered to the client for scheduled/proactive playback.

---

# Morning Brief / Good Night Summary

These are proactive-speech exceptions.

The backend provides the canonical text/audio content needed by the client. The client automatically starts Speak Aloud at the scheduled delivery time and provides Stop.

Ordinary Butler-response notifications remain silent by default.

---

# Authentication

Existing authenticated-user boundaries remain unchanged:

- email/password and Google sign-in may establish Butler identity
- protected endpoints derive user identity from verified authentication
- client-provided `user_id` is never authority
- access/refresh-token handling remains deterministic application behavior

---

# Persistence

PostgreSQL remains authoritative for server state, including users, authentication identities/sessions, User Context, conversation text history, Daily Plans, Daily Events, request lifecycle/result metadata, devices, and synchronization metadata.

Temporary audio assets may live outside PostgreSQL, with database metadata referencing them when required.

---

# Data Ownership

Backend owns:

- authoritative authenticated identity
- User Context
- server conversation text
- Daily Plans / Daily Events after reconciliation
- Butler request lifecycle/result metadata
- response text
- temporary response audio assets
- completion push publication

Client owns:

- local audio recording before upload
- immediate local UI state
- local response-audio cache/history
- foreground/background fetch execution
- playback routing and notification presentation
- offline queues and device execution state

---

# Guiding Architecture

```text
                    ANDROID
                       │
             compressed audio request
                       │
                       ▼
                      API
                       │
                       ▼
                 APPLICATION
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       Butler       Planning      Sync/Auth
          │
          ▼
        DOMAIN
          │
   ┌──────┴────────┐
   ▼               ▼
PostgreSQL      AI / Audio
   │               │
   └──────┬────────┘
          ▼
    saved result
          ↓
         FCM
          ↓
       ANDROID
```

Keep the backend request path easy to trace. Add worker/platform complexity only when real production load requires it.
