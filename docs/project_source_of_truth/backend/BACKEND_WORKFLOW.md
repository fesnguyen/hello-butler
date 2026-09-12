# Backend Workflow

**Version:** 1.5  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines backend execution flows for Butler requests, planning, persistence, and synchronization.

---

# Input Flows

## Order / Talk — audio ingress

```text
POST /api/butler/requests/audio
        ↓
authenticate
        ↓
validate mode = order | talk + audio
        ↓
create durable request
        ↓
return 202 + request_id
        ↓
continue asynchronously
        ↓
transcribe / understand audio
        ↓
NormalizedButlerRequest(message=transcript)
```

## Text — text ingress

```text
POST /api/butler/requests/text
        ↓
authenticate
        ↓
validate typed message
        ↓
create durable request
        ↓
return 202 + request_id
        ↓
continue asynchronously
        ↓
NormalizedButlerRequest(message=submitted text)
```

Text does not use audio capture, STT, transcription, or a server-prepared editable-draft phase.

---

# Shared Butler Workflow

Both ingress paths join here:

```text
NormalizedButlerRequest
        ↓
load newest relevant conversation
+ User Context
+ Daily Plan / Events
+ now / timezone
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
persist user message + Butler message + result metadata
        ↓
mark request completed
        ↓
FCM completed(request_id)
```

Do not fork this workflow based on `input_source` after normalization unless a concrete behavior requires it.

---

# Request State and Client Presentation

Server lifecycle:

```text
accepted → processing → completed | failed
```

For audio requests, the client initially shows:

```text
Sending...
   ↓
accepted / handling signal
   ↓
Sent • time
   ↓
completion
   ↓
replace placeholder with server transcript
```

For text requests, the client already knows the exact user message, so it keeps that text visible while only delivery state changes:

```text
<typed message> + Sending...
   ↓
<typed message> + Sent
   ↓
completion
   ↓
append Butler response
```

The backend does not persist Sending/Sent as conversation text.

---

# Context Loading

Use bounded relevant context:

- newest relevant conversation messages
- User Context
- target/current Daily Plan
- relevant Daily Events
- current date/time/timezone

Recent conversation resolves references and continues the immediate exchange. Older conversation must not override a newer explicit request.

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

Understand the information need, load relevant state, answer, and persist the conversation.

## Clarify

Clarification should be concise and natural. Ask only when important information cannot reasonably be inferred from the newest message, relevant recent conversation, current time/timezone, events, or User Context.

---

# Order / Talk Semantics

Order prioritizes unattended execution; the user may leave after upload. Talk prioritizes conversational response but still uses asynchronous transport. Neither requires a realtime media/WebRTC session.

---

# Text Semantics

Text is a direct typed input path into the same Butler workflow.

```text
user types/edits
   ↓
Send
   ↓
text request accepted
   ↓
shared Butler workflow
   ↓
normal Butler response
```

Text is not a semantic intent. It normally uses the conversational/Talk expectation while the shared reasoning layer determines command, query, or clarify from the actual message.

---

# Result Persistence

Every completed interaction persists exactly one user message and one Butler message:

```text
ConversationHistory
├── role=user
│   ├── audio source → final transcript
│   └── text source  → submitted text
└── role=butler → canonical response text
```

The result references Butler response audio and changed domain entities where relevant. Do not create separate records for notification versus in-app delivery.

---

# Response Audio and Completion

```text
final Butler response text
      ↓
audio generation
      ↓
temporary server audio asset
      ↓
persist canonical result
      ↓
commit
      ↓
FCM butler_request_completed(request_id)
```

Push failure must not roll back the canonical result. Audio retention is limited and cleaned regularly.

---

# Result Retrieval

```text
GET /api/butler/requests/{request_id}
      ↓
user message text
Butler response text
response audio URL/reference
changed entities / status metadata
```

Fetch is idempotent and safe to retry from foreground or WorkManager.

---

# Morning Brief / Good Night Summary

These remain proactive-speech exceptions. Prepared canonical content becomes due, the client automatically starts Speak Aloud, and the user can Stop immediately. Ordinary Butler messages remain silent unless the user selects a playback action.

---

# Direct Event Updates

Direct visible event edits bypass AI and remain local-first: Room updates immediately, sync is queued, backend validates/reconciles, then Room receives canonical state.

---

# Reliability Rules

- request creation must tolerate retries
- domain mutations must be idempotent
- result fetch must be retryable
- FCM is a hint, never the only canonical copy
- background work may be delayed or repeated
- push/audio-storage failure must not corrupt domain state
- AI never directly owns database mutation
- conversation text is durable; server audio is not
- input transport must not duplicate Butler reasoning logic

---

# Guiding Workflow

```text
What transport arrived?
      ↓
Normalize it to message text
      ↓
What does the user mean?
      ↓
What state should change?
      ↓
What should Butler say?
      ↓
Generate one canonical text + audio response
```
