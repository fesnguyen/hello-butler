# Backend Architecture

**Version:** 1.6  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

The backend owns Butler intelligence, authoritative server state, asynchronous request processing, multimodal AI orchestration, conversation persistence, response-audio handling, authentication, planning, and synchronization.

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

API owns transport/authentication. Application owns orchestration and transaction boundaries. Domain owns product concepts/rules. Infrastructure owns PostgreSQL, multimodal AI/audio providers, push, storage, and other integrations.

---

# Two Ingress APIs, One Butler Product Workflow

```text
                 API
          ┌───────┴───────┐
          │               │
      AUDIO INPUT      TEXT INPUT
      Order/Talk          Text
          │               │
   POST audio API    POST text API
          │               │
          └───────┬───────┘
                  ↓
          Butler application
                  ↓
      load text context / state
                  ↓
       multimodal AI boundary
                  ↓
       command/query/clarify
                  ↓
        tools / domain / DB
                  ↓
       canonical result
          text + audio
```

Do not create separate Butler brains for audio and text. The application/domain behavior remains shared.

The important distinction is that an Order/Talk audio request must preserve the original audio through the AI reasoning boundary.

---

# Primary API Boundaries

Conceptually:

```text
POST /api/butler/requests/audio
POST /api/butler/requests/text
GET  /api/butler/requests/{request_id}
GET  /api/butler/requests/{request_id}/audio
```

## Audio request

```text
POST /api/butler/requests/audio
Content-Type: multipart/form-data

interaction_mode = order | talk
audio = compressed recording
```

## Text request

```text
POST /api/butler/requests/text
Content-Type: application/json

message = <typed text>
```

Both return quickly:

```text
202 Accepted
request_id = <stable id>
```

Both then use the same asynchronous request/result lifecycle.

---

# Multimodal Butler Input Contract

For Order/Talk, the application assembles one AI input that contains:

```text
original recorded audio
+
textual context
├── Butler instructions
├── interaction mode
├── newest relevant conversation
├── User Context / preferences
├── current/relevant Daily Plan and Events
└── now / timezone
```

The AI/provider boundary must be capable of understanding the original audio together with this textual context.

The normal architecture must not be:

```text
audio
 ↓
standalone STT
 ↓
transcript only
 ↓
text-only Butler reasoning
 ↓
standalone TTS
```

That design throws away the original audio before semantic reasoning and makes transcription the single point of failure.

A transcript/understood utterance text is still required for history and UI, but it is part of the multimodal result rather than the sole reasoning input.

---

# Text Input Contract

For Text, the AI input contains:

```text
typed message
+
same textual Butler context
```

No microphone/STT step is involved.

Text remains an input method, not a semantic intent. Semantic intent remains command, query, or clarify.

---

# AI Result Contract

The multimodal/provider result must support the application with the information needed to complete the Butler request, conceptually:

```text
AI result
├── user_message_text
│   ├── audio source → transcript / understood utterance text
│   └── text source  → submitted text
├── structured decision / action proposal when needed
├── Butler response text
└── Butler response audio
```

If tool/domain actions are required, application/domain code remains authoritative for mutation. The AI may propose intent/actions, but must not write directly to the database.

The provider/orchestration layer may use a tool-capable multimodal interaction internally, but the original audio must remain available to the AI reasoning path until the user's meaning is resolved.

---

# Request Lifecycle

Both transports share durable states:

```text
accepted
processing
completed
failed
```

The client may present `Sending...` and `Sent • time` while the server request progresses.

---

# Shared Processing

```text
accepted request
      ↓
load request input
      ↓
load relevant text context
      ↓
AI processing
  ├── audio → original audio + text context
  └── text  → typed text + text context
      ↓
resolve command / query / clarify
      ↓
apply validated deterministic actions when needed
      ↓
finalize canonical Butler response
      ├── text
      └── audio
      ↓
persist conversation/result metadata
      ↓
save response audio temporarily
      ↓
mark completed
      ↓
FCM completed(request_id)
```

The architecture should preserve a single canonical request/result even if internal AI orchestration requires more than one provider round trip.

---

# Canonical Result Contract

A completed request conceptually contains:

```text
request_id
input_source
user_message_text
Butler response text
Butler response audio reference
changed entity metadata when relevant
completion timestamp
```

For audio, `user_message_text` is the multimodal result transcript/understood utterance text. For text, it is the exact submitted text.

One completed request produces one user conversation message and one Butler conversation message. Notification and in-app presentation are not separate results.

---

# Response Audio Storage and Delivery

The AI/provider result includes Butler response audio as part of the completed interaction.

The backend stores that audio temporarily and exposes an authenticated download endpoint/reference. It does not embed the audio bytes in FCM.

```text
AI returns response text + audio
      ↓
backend persists canonical text/result
backend writes response audio asset
      ↓
FCM completed(request_id)
      ↓
client GETs canonical result text/metadata
      ↓
client immediately downloads response audio
      ↓
local cache
```

The text result must remain available even if audio download is delayed. Backend response audio is retention-limited.

---

# Push and Result Fetch

FCM is a wake-up signal, not canonical content.

```text
request completed
   ↓
FCM: butler_request_completed + request_id
   ↓
GET /api/butler/requests/{request_id}
   ↓
canonical text result + response audio reference
   ↓
GET authenticated response audio
```

Push failure must not roll back a completed request. Result fetch must be idempotent and retryable.

---

# Conversation Persistence

Backend conversation text is durable:

```text
role=user   → transcript/understood utterance OR submitted typed text
role=butler → canonical response text
```

Audio is not the permanent server conversation record. Temporary client Sending/Sent placeholders are not backend conversation content.

---

# Domain Actions

AI may decide what the user intends, but deterministic application/domain code validates and applies important mutations, including event creation/update/skip, User Context changes, queries, and replanning.

---

# Planning and Proactive Speech

The existing Daily Plan / Daily Event lifecycle remains authoritative. Morning Brief and Good Night Summary are proactive-speech exceptions: backend provides canonical content/audio; client automatically starts Speak Aloud when due and always allows Stop. Ordinary Butler responses remain silent by default.

---

# Authentication and Persistence

Protected endpoints derive identity from verified authentication; client-provided `user_id` is never authority.

PostgreSQL remains authoritative for users, User Context, conversation text, Daily Plans/Events, request/result metadata, devices, and synchronization metadata. Temporary audio assets may live outside PostgreSQL with referenced metadata.

---

# Guiding Rule

Preserve the richest user input through the reasoning boundary:

```text
AUDIO + TEXT CONTEXT ──┐
                       ├── Butler reasoning/application ── text + audio result
TEXT + TEXT CONTEXT ───┘
```

A standalone transcript may be stored and displayed, but it must not be the only semantic representation of an Order/Talk request before Butler reasoning.
