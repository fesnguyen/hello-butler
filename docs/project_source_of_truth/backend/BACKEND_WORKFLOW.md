# Backend Workflow

**Version:** 1.6  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines backend execution flows for Butler requests, planning, persistence, multimodal AI processing, and synchronization.

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
load original recorded audio
        ↓
load textual Butler context
        ↓
multimodal AI processing with audio + text context
```

The original audio must remain part of the AI reasoning input. Do not reduce the request to a standalone STT transcript before Butler understands the user's meaning.

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
load typed text + textual Butler context
        ↓
shared Butler reasoning/application behavior
```

Text does not use audio capture or STT.

---

# Context Loading

Before AI reasoning, load bounded relevant textual context:

- Butler instructions
- interaction mode
- newest relevant conversation messages
- User Context / preferences
- target/current Daily Plan
- relevant Daily Events
- current date/time/timezone

For Order/Talk, this text context is sent together with the original recorded audio.

Recent conversation resolves references and continues the immediate exchange. Older conversation must not override a newer explicit request.

---

# Multimodal Order / Talk Workflow

```text
original audio
+
text context
        ↓
audio-capable multimodal Butler model
        ↓
understand speech + meaning together
        ↓
produce/resolve
   ├── user transcript / understood utterance text
   ├── semantic intent
   ├── structured action proposal when needed
   ├── response text
   └── response audio
        ↓
validate/apply deterministic domain actions when needed
        ↓
finalize canonical result
```

The normal path is **not**:

```text
audio → STT → transcript-only reasoning → separate TTS
```

A transcript is required for conversation history, but it is an output/artifact of multimodal understanding rather than the sole semantic input.

If the provider/tool loop needs multiple internal turns, preserve the original audio or equivalent multimodal context until the user's meaning is resolved.

---

# Text Workflow

```text
typed text
+
text context
      ↓
Butler reasoning
      ↓
command / query / clarify
      ↓
domain action when needed
      ↓
canonical response text + audio
```

Text remains an input method, not a semantic intent. It normally uses the conversational/Talk expectation while semantic intent is inferred from the message itself.

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

Clarification should be concise and natural. Ask only when important information cannot reasonably be inferred from the newest input, relevant recent conversation, current time/timezone, events, or User Context.

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
replace placeholder with multimodal transcript / understood utterance text
```

For text requests, the exact submitted text remains visible while only delivery state changes.

The backend does not persist Sending/Sent as conversation text.

---

# Result Persistence

Every completed interaction persists exactly one user message and one Butler message:

```text
ConversationHistory
├── role=user
│   ├── audio source → multimodal transcript / understood utterance
│   └── text source  → submitted text
└── role=butler → canonical response text
```

The request result also references Butler response audio and changed domain entities where relevant. Do not create separate records for notification versus in-app delivery.

---

# Response Text + Audio Completion

The completed AI/application result includes response text and response audio.

```text
canonical response text + audio
      ↓
persist text/result metadata
      ↓
save response audio temporarily on backend
      ↓
commit completed request
      ↓
FCM butler_request_completed(request_id)
```

The response audio is not sent through FCM.

---

# Result Retrieval and Audio Download

```text
GET /api/butler/requests/{request_id}
      ↓
user message text
Butler response text
response audio URL/reference
changed entities / status metadata
      ↓
client persists text result
      ↓
client immediately GETs response audio
      ↓
Room / local audio cache
```

Fetch is idempotent and safe to retry from foreground or WorkManager.

The backend keeps response audio long enough for normal download/recovery, then retention cleanup removes it.

---

# Order / Talk Semantics

Order prioritizes unattended execution; the user may leave after upload. Talk prioritizes conversational response but still uses asynchronous transport. Neither requires a realtime media/WebRTC session.

Realtime streaming is not required for the product behavior; recorded audio is uploaded after release and processed asynchronously.

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
- conversation text is durable; server audio is retention-limited
- input transport must not duplicate Butler product logic
- Order/Talk original audio must remain available through the semantic reasoning boundary
- transcript quality must not be allowed to become the sole determinant of user meaning

---

# Guiding Workflow

```text
What input arrived?
      ↓
Load the original input + relevant Butler context
      ↓
Understand meaning using the richest available modality
      ↓
What state should change?
      ↓
What should Butler say?
      ↓
Return one canonical text + audio result
```
