# Backend Workflow

**Version:** 1.8  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines backend execution flows for Butler requests, planning, persistence, AI processing, TTS, and synchronization.

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
interaction AI call
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
interaction AI call
```

Text does not use microphone capture or STT.

---

# Context Loading

Before the AI call, load enough authoritative context for the model to understand the request, propose any required state changes, and produce the final Butler response:

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

# Interaction Workflow

```text
original audio OR typed text
+
complete relevant Butler context
        ↓
interaction AI call
        ↓
AI result
├── user_message_text
├── proposed_updates
└── response_text
        ↓
validate proposed updates
        ↓
apply supported mutations
        ↓
persist canonical text result
        ↓
mark request completed + notify client
        ↓
TTS continues asynchronously
```

`response_text` is canonical. TTS receives that finalized text and must not independently reason, rewrite, or paraphrase it.

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

The model never writes directly to PostgreSQL.

If application validation cannot apply a proposed mutation, processing fails with an observable mutation-rejection error before conversation history or a completed request can persist the model's success claim.

---

# Semantic Routes

## Command

Load the relevant target state before the AI call. The model returns the understood utterance, proposed mutation, and response text. The backend validates and applies the mutation.

## Query / conversation

Load relevant state before the AI call. The model returns `proposed_updates = none` together with the response text.

## Clarify

When intent genuinely cannot be resolved, the interaction returns no mutation plus a concise natural clarification.

---

# Request State and Client Presentation

Server lifecycle remains:

```text
accepted → processing → completed | failed
```

`completed` means canonical text is ready for the client. It does not mean optional TTS/audio preparation is finished.

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

The request result references changed entities and response-audio state/reference where relevant.

---

# Response Audio Completion

TTS is outside the text-completion critical path:

```text
canonical response_text
      ↓
commit completed request
      ↓
FCM butler_request_completed(request_id)
      ↓
client can show text immediately
      │
      └── shared TTS service
              ↓
          synthesize
              ↓
          normalize/store audio
              ↓
          audio ready
              ↓
          client reconciles/downloads
```

TTS failure preserves the completed canonical text response and must not convert successful reasoning into an HTTP 500 solely because speech generation failed.

---

# Result Retrieval and Audio Download

```text
GET /api/butler/requests/{request_id}
      ↓
user message text
Butler response text
response audio state/reference when available
changed entities / status metadata
      ↓
client persists/shows text immediately
      ↓
client downloads response audio when ready
      ↓
Room / local audio cache
```

Fetch remains idempotent and safe to retry from foreground or WorkManager. Backend response audio remains retention-limited.

---

# Order / Talk Semantics

Order prioritizes unattended execution; Talk prioritizes conversational response. Both remain recorded asynchronous interactions rather than realtime media/WebRTC sessions.

---

# Upcoming Event Derivation and Mutation

When serving planning/presentation context, derive Upcoming Events from actionable User Context whose applicable period is active today or in the future. Do not persist a duplicate Upcoming Event entity.

```text
User Context
      ↓ filter active/future actionable context
Upcoming projection
      ├── supplied as one input to planning
      └── supplied for client presentation
```

Daily planning combines this projection with routines, preferences, constraints, existing events, and other relevant context. It may materialize zero, one, or multiple Daily Events as appropriate; there is no rule that every Upcoming Event becomes a Daily Event. Daily routines bypass the Upcoming classification and remain routine planning context.

For an Upcoming Event update/reschedule/skip/remove request, resolve the projected item to its source User Context and mutate that source. Recurring context must support the semantic difference between changing one occurrence and changing/removing the recurring rule.

# Direct Event Updates

Direct visible Daily Event edits remain deterministic/local-first: Room updates immediately, sync is queued, backend validates/reconciles, then Room receives canonical state. Upcoming Event edits target User Context and therefore follow the appropriate User Context mutation/reconciliation path rather than being persisted as Daily Event edits.

---

# Credits and TTS Resolution

Before a paid Butler reasoning/planning operation, load the authenticated user's backend-authoritative credit balance. If the operation requires paid AI and credits are insufficient, handle that state explicitly through the application/API contract. Open-source TTS is not a replacement for reasoning.

All Butler speech uses the same shared TTS service, including conversation responses, Morning Brief, and Good Night Summary:

```text
canonical speech text
    ↓
Profile.tts_method
    ├── OPEN_SOURCE → Kokoro/open-source TTS
    └── OPENAI
           ├── credits > 0 → OpenAI TTS + deduct TTS credits
           └── credits <= 0 → Kokoro/open-source TTS
```

The effective fallback does not modify `Profile.tts_method`. Credit checks/deductions are backend responsibilities and the balance must not become negative.

Morning Brief and Good Night Summary should use this same service rather than Android native TTS. Their audio should be prepared early enough for the client to cache before scheduled playback when practical.

---

# Reliability Rules

- request creation must tolerate retries
- domain mutations must be idempotent
- AI-proposed mutations must be validated before persistence
- canonical text completion must not wait for TTS
- TTS failure must not invalidate a completed text result
- all Butler speech uses the shared backend TTS service
- result/audio fetch must be retryable
- FCM is a hint, never the only canonical copy
- background work may be delayed or repeated
- push/audio-storage failure must not corrupt domain state
- AI never directly owns database mutation
- conversation text is durable; server audio is retention-limited
- Order/Talk original audio must reach the semantic reasoning boundary

---

# Guiding Workflow

```text
What input arrived?
      ↓
Load enough authoritative context before AI
      ↓
interaction AI call
      ↓
Transcript + proposed changes + response text
      ↓
Validate/apply changes
      ↓
Persist + deliver canonical text
      ↓
Shared TTS asynchronously
```
