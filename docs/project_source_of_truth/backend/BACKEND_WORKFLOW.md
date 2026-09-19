# Backend Workflow

**Version:** 1.7  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines backend execution flows for Butler requests, planning, persistence, single-call multimodal AI processing, and synchronization.

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
load complete relevant Butler context
        ↓
ONE multimodal AI API call
```

The original audio remains part of the model input. Do not reduce it to standalone STT before Butler reasoning.

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
load typed text + complete relevant Butler context
        ↓
ONE multimodal/provider API call
```

Text does not use microphone capture or STT.

---

# Context Loading

Before the single AI call, load enough authoritative context for the model to understand the request, propose any required state changes, and produce the final Butler response without a second AI round trip:

- Butler instructions
- interaction mode
- newest relevant conversation messages
- User Context / preferences
- current/target Daily Plan
- relevant Daily Events, including identifiers and current values needed for mutation
- current date/time/timezone
- supported mutation/result contract

Context should be bounded and relevant. Newer explicit user intent overrides unrelated or superseded history.

---

# Single-Call Multimodal Workflow

```text
original audio OR typed text
+
complete relevant Butler context
        ↓
┌──────────────────────────────────────────┐
│       ONE multimodal provider call       │
│                                          │
│ understand input                         │
│ resolve intent                           │
│ decide proposed state changes            │
│ compose final Butler response text       │
│ generate matching response audio         │
└──────────────────┬───────────────────────┘
                   ↓
AI result
├── user_message_text
├── proposed_updates
├── response_text
└── response_audio matching response_text
                   ↓
validate proposed updates
                   ↓
apply supported mutations
                   ↓
normalize/store returned audio if needed
                   ↓
persist one canonical result
```

The normal path is **not**:

```text
audio → STT → reasoning → domain execution → second AI/TTS call
```

and is also not:

```text
multimodal understanding → domain execution → second AI audio-generation call
```

There must normally be one OpenAI/multimodal provider API request for one Butler interaction.

---

# AI Output

The provider result must carry all information required after the call:

```text
user_message_text
proposed_updates
response_text
response_audio
```

`proposed_updates` may contain supported changes such as:

- Daily Event add/create
- Daily Event update/move
- Daily Event remove
- Daily Event skip
- other supported Daily Event mutations
- User Context / preference changes
- no changes for a query or conversational response

Response audio must speak the same canonical content as `response_text`. It must not independently elaborate, contradict, or replace the returned text.

---

# Validation and Mutation

AI proposes changes; application/domain code validates and applies them.

```text
proposed update
      ↓
validate target + values + supported operation
      ↓
valid?
  ├── yes → apply/persist idempotently
  └── no  → fail/reconcile through deterministic software behavior
```

The backend does not make a second AI call merely to rewrite the response after mutation. The design intentionally relies on complete pre-call context and the model's single result.

The model never writes directly to PostgreSQL.

The structured tool payload contains `user_message_text`, the typed `ButlerDecision`,
and `response_text`. Native model audio remains separate from that JSON payload at
the provider boundary. The application combines both parts into one internal
interaction result; base64 audio is never modeled as an arbitrary mutation field.

If application validation cannot apply a proposed mutation, processing fails with
an observable mutation-rejection error before conversation history or a completed
request can persist the model's success claim. No second model call rewrites that
claim. If only returned response audio is missing or malformed, the canonical text
result may complete with an audio-unavailable warning; the backend never invokes a
separate TTS fallback.

---

# Semantic Routes

## Command

Load the relevant target state before the AI call. The model returns the understood utterance, proposed mutation, response text, and matching audio together. The backend validates and applies the mutation.

## Query / conversation

Load relevant state before the AI call. The model returns `proposed_updates = none` together with the response text/audio.

## Clarify

When intent genuinely cannot be resolved, the same single call returns no mutation plus a concise natural clarification in both text and matching audio.

---

# Request State and Client Presentation

Server lifecycle remains:

```text
accepted → processing → completed | failed
```

For audio requests, the client initially shows Sending/Sent state and replaces the temporary user content with `user_message_text` on completion. For text requests, the exact submitted text remains visible.

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

The request result references response audio and changed entities where relevant.

---

# Response Audio Completion

```text
response audio from the same AI call
      ↓
normalize/finalize media container if required
      ↓
store temporarily on backend
      ↓
persist canonical text/result metadata
      ↓
commit completed request
      ↓
FCM butler_request_completed(request_id)
```

FFmpeg/container normalization is allowed after the AI call because it only makes returned media reliably playable; it must not regenerate or alter the spoken message.

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

Fetch remains idempotent and safe to retry from foreground or WorkManager. Backend response audio remains retention-limited.

---

# Order / Talk Semantics

Order prioritizes unattended execution; Talk prioritizes conversational response. Both remain recorded asynchronous interactions rather than realtime media/WebRTC sessions.

---

# Direct Event Updates

Direct visible event edits bypass AI and remain local-first: Room updates immediately, sync is queued, backend validates/reconciles, then Room receives canonical state.

---

# Reliability Rules

- request creation must tolerate retries
- domain mutations must be idempotent
- AI-proposed mutations must be validated before persistence
- result fetch must be retryable
- FCM is a hint, never the only canonical copy
- background work may be delayed or repeated
- push/audio-storage failure must not corrupt domain state
- AI never directly owns database mutation
- conversation text is durable; server audio is retention-limited
- Order/Talk original audio must reach the semantic reasoning boundary
- normal Butler processing uses one multimodal provider API call per interaction
- response text and response audio from that call represent the same Butler message

---

# Guiding Workflow

```text
What input arrived?
      ↓
Load enough authoritative context before AI
      ↓
ONE multimodal call
      ↓
Transcript + proposed changes + response text + matching audio
      ↓
Validate/apply changes
      ↓
Persist and deliver one canonical result
```
