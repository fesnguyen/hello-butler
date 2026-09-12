# Backend Workflow

**Version:** 1.4  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines backend execution flows for Butler requests, planning, persistence, and synchronization.

---

# Core Butler Request Flow

Order and Talk are recorded-audio requests processed asynchronously.

```text
POST /api/butler/requests
        ↓
authenticate
        ↓
validate mode + audio
        ↓
create request record
        ↓
return 202 + request_id
        ↓
publish/emit handling signal when accepted
        ↓
continue processing asynchronously
        ↓
understand/transcribe audio
        ↓
load recent conversation + User Context + relevant Daily Plan/Events
        ↓
understand semantic intent
        ↓
route
   ┌────┼────┐
   ▼    ▼    ▼
COMMAND QUERY CLARIFY
   │    │    │
   └────┼────┘
        ↓
apply validated deterministic actions when required
        ↓
produce Butler response text
        ↓
generate Butler response audio
        ↓
persist transcript + Butler text + result metadata
        ↓
mark request completed
        ↓
FCM completed(request_id)
```

The request must survive the user leaving the app after upload.

---

# Client-Visible Sending / Sent State

The backend contract supports the client lifecycle:

```text
release recording
    ↓
client = Sending...
    ↓
server accepts / handling acknowledgement
    ↓
client = Sent • time
    ↓
server completes processing
    ↓
client fetches final result
    ↓
Sent placeholder replaced by user transcript
```

`Sending...` and `Sent` are client presentation states. They are not durable conversation messages on the backend.

---

# Speech Understanding

The backend receives the recorded audio rather than a locally transcribed Order/Talk message.

Speech understanding should preserve useful information from the original audio and produce a stable transcript for conversation history.

The transcript becomes the durable user-side conversation text after completion.

---

# Context Loading

Use bounded relevant context, including:

- newest relevant conversation messages
- User Context
- target/current Daily Plan
- relevant Daily Events
- current date/time/timezone

Recent conversation exists to resolve references and continue the immediate exchange. Older conversation must not override a newer explicit request.

---

# Semantic Routes

## Command

```text
understand requested change
      ↓
resolve target
      ↓
enough information?
  ├── no → clarify
  └── yes
        ↓
structured mutation proposal
        ↓
validate deterministically
        ↓
apply / persist
        ↓
confirmation response
```

## Query

```text
understand information need
      ↓
load relevant state
      ↓
reason / answer
      ↓
persist conversation
```

## Clarify

Clarification should be concise and natural. Ask only when important information cannot reasonably be inferred from the current message, recent conversation, current time/timezone, events, or User Context.

---

# Order

Order prioritizes unattended execution.

```text
voice request
   ↓
accepted quickly
   ↓
user may leave
   ↓
backend completes action / answer / clarification
   ↓
FCM completed
```

Order does not require the user to keep an active screen open.

---

# Talk

Talk prioritizes conversational response, but uses the same asynchronous transport.

```text
voice request
   ↓
accepted
   ↓
backend processes
   ↓
Butler message returned
   ↓
conversation can continue
```

Talk is not a realtime media/WebRTC call.

---

# Text

Text receives recorded audio but does not immediately execute it as a Butler action.

```text
recorded audio
   ↓
speech understanding
   ↓
relevant user preference + recent conversation
   ↓
rewrite / translate / normalize as appropriate
   ↓
editable text result
```

The client presents the result for editing. Explicit later use of that text is a separate user action.

---

# Result Persistence

A completed Order/Talk request persists:

```text
ConversationHistory
├── role=user   → final transcript
└── role=butler → canonical Butler response text
```

The same result may also reference response audio and changed domain entities.

Do not create separate conversation records for notification delivery versus in-app delivery.

---

# Response Audio Generation

Response text is finalized before response audio generation.

```text
Butler response text
      ↓
audio generation
      ↓
temporary server audio asset
      ↓
result metadata references asset
```

Audio retention is limited and cleaned regularly.

---

# Completion Push

After the canonical result has been saved:

```text
persist completed result
      ↓
commit
      ↓
FCM butler_request_completed(request_id)
```

Push failure must not roll back the canonical result.

FCM contains identifiers/hints, not the audio file.

---

# Result Retrieval

After completion signal:

```text
GET /api/butler/requests/{request_id}
      ↓
user transcript
Butler response text
response audio URL/reference
changed entities / status metadata
```

The fetch is idempotent and safe to retry from foreground or WorkManager.

---

# Morning Brief / Good Night Summary

Planning remains separate from ordinary Butler requests.

```text
prepare content
      ↓
persist Daily Event / canonical result
      ↓
client syncs/caches required content
      ↓
due time arrives
      ↓
client auto-starts Speak Aloud
      ↓
user can Stop immediately
```

These are proactive-speech exceptions. Ordinary Butler message delivery stays silent unless the user explicitly selects an audio action.

---

# Direct Event Updates

Direct visible event edits bypass AI:

```text
client edit
   ↓
local-first Room update
   ↓
sync operation
   ↓
backend validates version
   ↓
persist canonical state
   ↓
return/reconcile
```

---

# Daily Lifecycle

```text
Night
  ↓
prepare tomorrow
  ↓
create/update Daily Events
  ↓
generate Morning Brief
  ↓
persist

Day
  ↓
execute / adapt / replan

Night
  ↓
read final day reality
  ↓
generate Good Night Summary
  ↓
prepare tomorrow
```

---

# Reliability Rules

- request creation must be idempotent where retries can occur
- result fetch must be retryable
- FCM is a hint, never the only copy of canonical data
- background work may be delayed or retried
- push/audio-storage failure must not corrupt domain state
- AI never directly owns database mutation
- conversation text is durable; server audio is not

---

# Guiding Workflow

The backend should answer:

```text
Who is the user?
What did they say?
What do they mean?
What state should change?
What should Butler say?
What audio represents that response?
```

Android answers:

```text
How should this one canonical message be presented and played right now?
```
