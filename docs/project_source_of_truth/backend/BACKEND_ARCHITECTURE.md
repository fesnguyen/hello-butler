# Backend Architecture

**Version:** 1.5  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

The backend owns Butler intelligence, authoritative server state, asynchronous request processing, conversation persistence, response audio generation, authentication, planning, and synchronization.

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

API owns transport/authentication. Application owns orchestration and transaction boundaries. Domain owns product concepts/rules. Infrastructure owns PostgreSQL, AI/audio providers, push, storage, and other integrations.

---

# Two Ingress APIs, One Butler Workflow

Order/Talk and Text use different input transports but converge before Butler reasoning.

```text
                 API
          ┌───────┴───────┐
          │               │
      AUDIO INPUT      TEXT INPUT
      Order/Talk          Text
          │               │
   POST audio API    POST text API
          │               │
      transcribe           │
          └───────┬───────┘
                  ↓
          normalized message
                  ↓
          Butler application
                  ↓
            Butler graph
                  ↓
       command/query/clarify
                  ↓
        tools / domain / DB
                  ↓
       canonical result
          text + audio
```

Do not create separate AudioGraph and TextGraph implementations. After normalization, downstream Butler reasoning should consume the same request contract.

---

# Primary API Boundaries

Conceptually:

```text
POST /api/butler/requests/audio
POST /api/butler/requests/text
GET  /api/butler/requests/{request_id}
```

Exact route naming may evolve during implementation, but the architectural separation is required: one audio ingress for Order/Talk and one text ingress for typed Text.

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

# Normalized Butler Request

Transport-specific processing ends at a normalized application contract, conceptually:

```text
NormalizedButlerRequest
├── request_id
├── user_id
├── message
├── interaction_mode
└── input_source = audio | text
```

For audio, `message` is the server-produced transcript. For text, `message` is the submitted text.

`input_source` is provenance/operational metadata. It must not cause duplicate reasoning architectures.

Text is an input source, not a semantic intent. Semantic intent remains command, query, or clarify. Typed Text normally uses the conversational/Talk expectation unless a future product requirement introduces an explicit alternative.

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

Audio processing includes transcription before normalization. Text skips transcription.

---

# Shared Processing

```text
accepted request
      ↓
normalize input
  ├── audio → transcribe
  └── text  → submitted text
      ↓
load relevant conversation + User Context + Daily state
      ↓
Butler reasoning
      ↓
validated deterministic actions when needed
      ↓
finalize response text
      ↓
generate response audio
      ↓
persist canonical conversation/result
      ↓
mark completed
      ↓
FCM completed(request_id)
```

Application/domain code remains authoritative for mutations. AI/provider code must not write directly to the database.

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

For audio, `user_message_text` is the transcript. For text, it is the exact submitted text.

One completed request produces one user conversation message and one Butler conversation message. Notification and in-app presentation are not separate results.

---

# Response Audio

Response audio is generated after Butler response text is finalized for both audio and text requests.

Backend response audio has limited retention. Uploaded user audio is temporary processing data and should be removed sooner after successful processing. The backend does not promise permanent historical audio restoration.

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
canonical result + response audio reference
```

Push failure must not roll back a completed request. Result fetch must be idempotent and retryable.

---

# Conversation Persistence

Backend conversation text is durable:

```text
role=user   → transcript OR submitted typed text
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

Keep transport normalization at the edge:

```text
AUDIO ──transcribe──┐
                    ├── normalized message ── Butler workflow
TEXT ───────────────┘
```

Everything after that boundary should be shared unless a concrete product requirement proves otherwise.
