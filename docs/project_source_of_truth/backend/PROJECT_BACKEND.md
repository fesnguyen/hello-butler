# PROJECT_BACKEND — Backend Architecture and Workflow

**Status:** Source of Truth · **Authority:** [PROJECT.md](../PROJECT.md) → [ENGINEERING.md](../ENGINEERING.md) → this subsystem document → feature documents.

## Scope and navigation

This is the backend's primary technical entry point. It consolidates the former backend architecture and workflow documents without changing implementation contracts. Read [TTS.md](TTS.md) for speech-specific details. Product requirements live in [PROJECT.md](../PROJECT.md); shared engineering rules live in [ENGINEERING.md](../ENGINEERING.md).

## Architecture

**Version:** 2.0  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

### Purpose and Pattern

The backend owns Butler intelligence, authoritative server state, asynchronous request processing, conversation persistence, planning, TTS, authentication, and synchronization.

```text
API → Application → Domain
          ↑
    Infrastructure
```

API owns transport/authentication. Application owns orchestration/transactions. Domain owns product rules. Infrastructure owns PostgreSQL, AI/TTS providers, push, and audio storage.

---

### One Butler Workflow

```text
Audio (Order/Talk) ─┐
                    ├→ Butler application → context → interaction AI
Text ───────────────┘                         ↓
                                      validate/apply mutations
                                             ↓
                                      canonical text result
                                             ↓
                                      asynchronous TTS
```

Order/Talk preserves original recorded audio through the interaction call. Text uses submitted text directly. Do not create separate reasoning systems for input modes.

Conceptual request boundaries remain:

```text
POST /api/butler/requests/audio
POST /api/butler/requests/text
GET  /api/butler/requests/{request_id}
GET  /api/butler/requests/{request_id}/audio
```

---

### Interaction Contract

Before the AI call, load relevant authoritative context: recent conversation, User Context/preferences, relevant Daily Plan/Events, current time/timezone, interaction mode, and identifiers needed for supported mutations.

The result contains:

```text
user_message_text
proposed supported mutation (or none)
canonical Butler response_text
```

Application/domain code validates and persists mutations. The AI never writes directly to PostgreSQL.

One completed request produces one user conversation message and one Butler message. For audio, user text is the transcript/understood utterance; for Text, it is the submitted text.

---

### Request and Audio Lifecycle

```text
accepted → processing → completed | failed
```

`completed` means canonical text/actions are ready; optional speech may still be preparing.

```text
interaction complete
      ↓
persist text/actions
      ↓
mark completed + notify client
      ↓
asynchronous shared TTS
      ↓
pending → processing → ready | unavailable
```

TTS failure never turns an already successful Butler interaction into a failed request.

---

### Shared TTS Service

Conversation responses, Morning Brief, and Good Night Summary use the same service:

```text
canonical text
      ↓
User/Profile.tts_method
├── OPEN_SOURCE → Kokoro
└── OPENAI
      ├── credits available → OpenAI TTS
      └── insufficient credits → Kokoro runtime fallback
```

Do not duplicate provider selection, credit reserve/refund, encoding, storage, or failure policy in feature-specific code. Runtime fallback does not modify the stored TTS preference.

Generated speech remains compatible with the authenticated Ogg/Opus transport/cache contract. Client Listen Aloud and Phone Listen are two playback routes for the same generated asset; the backend does not generate separate audio for each route.

Current implementation boundary: `app.application.speech.SpeechService` owns provider selection, credit reserve/refund, WAV validation, Ogg/Opus encoding, and temporary storage. Request/event audio status tracks speech readiness independently from canonical text.

---

### User/Profile, Credits, and User Context

Profile/settings persistence owns application configuration such as credits and TTS method. User Context owns Butler knowledge about the user's life. Do not create a generic settings table for current scope.

Credits are backend-authoritative Hello Butler product credits. The client cannot set its own balance. OpenAI TTS has an additional credit cost; Kokoro does not.

---

### Notes and Preferences

User Settings exposes user-manageable **Notes & Preferences** through the existing User Context/preference persistence. Do **not** add a standalone Note table or duplicate preference store.

A manageable saved item has:

```text
stable identity
text / description
preference semantics (yes/no)
normal User Context metadata required by existing architecture
```

The exact persistence representation may use an existing type/category or a minimal extension to User Context. Preserve existing architecture rather than creating a parallel entity solely to support the UI.

Semantics are important:

- a **preference** may be included as personalization/planning context when relevant;
- an ordinary **note** is saved/retrievable information and must not automatically be treated as a personalization preference.

The API/application boundary must support listing user-manageable saved items and adding, editing, deleting, or changing preference semantics. These operations mutate authoritative User Context.

Conversation actions such as `remember_user_context` / `update_user_context` that create or change a user-manageable preference must be visible through the same listing/sync contract. User Settings must therefore not rely on a separate profile-only preference collection.

The user-manageable projection excludes routines, temporary/one-time planning context, Upcoming Events, and internal metadata unless product semantics explicitly classify the record as a manageable note/preference.

Implementation uses the existing string `context_type`: `preference` or `note`, with non-actionable records only. No PostgreSQL schema change is required. `GET /api/user-settings/saved-context` returns this projection; `PUT /saved-context/{id}` accepts content, is_preference, and base_version (0 for creation); `DELETE /saved-context/{id}?base_version=…` soft-deletes. UUIDs are stable, create retries are idempotent, and stale conflicting edits return 409. The legacy account preference-deletion contract remains compatible with older clients.

Ordinary notes are excluded from automatic interaction/day-planning/evening context loading and remain retrievable through User Settings. Existing generic `reference` rows are not guessed to be preferences or reclassified automatically; an explicit Butler update can classify a known record as a preference.


---

### Synchronization Contract

Backend state is authoritative. The client may cache a user-manageable notes/preferences projection in Room, but reconciliation must converge with User Context.

After relevant conversational mutations, the result/sync metadata should give the client enough information to trigger reconciliation without guessing from response prose. Direct settings mutations return enough stable identity/state for idempotent reconciliation.

Protected endpoints derive identity from verified authentication; client-provided `user_id` is never authority.

---

### Planning and Upcoming Events

Daily Plan / Daily Event lifecycle remains authoritative. Upcoming Events are derived projections of actionable User Context, not persisted domain entities.

Upcoming Events are one planning input among routines, preferences, constraints, existing events, and other context. Mutations resolve to underlying User Context and distinguish occurrence-level exceptions from recurring-rule changes.

Morning Brief and Good Night Summary use the shared TTS service. Ordinary Butler responses remain silent until client playback is selected.

---

### Conversation and Push

Backend conversation text is durable:

```text
role=user   → transcript/understood utterance OR submitted text
role=butler → canonical response text
```

Audio is temporary server data. FCM is a wake-up signal, not canonical content. Result fetch and audio download remain idempotent/retryable; push failure does not roll back a completed request.

---

### Guiding Rule

```text
rich input + authoritative context
            ↓
     interaction AI
            ↓
validated mutation + canonical text
            ↓
       persist/deliver
            ↓
 asynchronous shared TTS
```

Keep one authoritative model for each concept, and do not make canonical text delivery wait for optional speech.


### Ecosystem boundary

FastAPI owns only Hello Butler and its database/Alembic history. Showcase owns its PostgreSQL access through its own server-side data layer in `web/showcase`. Future Admin will manage ecosystem databases through its trusted server-side layer. See [Showcase](../web/showcase/SHOWCASE.md).


#### On-demand reminder speech

Reminder playback reuses the shared `SpeechService` and existing DailyEvent audio fields. `POST /api/sync/events/{event_id}/speech?version=N` validates authenticated ownership, planned status, reminder type, and version; an unavailable owner transitions to pending and schedules generation. Existing pending/processing/ready speech is reused. The normal maintenance scan recovers pending reminders, and `GET /api/sync/events/{event_id}/audio` serves ready reminder audio. Content falls back to description/title. Showing notifications alone never requests synthesis. No provider settings or tables change.

---

## Execution workflows

**Version:** 1.8  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/PROJECT_BACKEND.md`

---

### Purpose

This document defines backend execution flows for Butler requests, planning, persistence, AI processing, TTS, and synchronization.

---

### Input Flows

#### Order / Talk — audio ingress

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

#### Text — text ingress

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

### Context Loading

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

### Interaction Workflow

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

### Validation and Mutation

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

### Semantic Routes

#### Command

Load the relevant target state before the AI call. The model returns the understood utterance, proposed mutation, and response text. The backend validates and applies the mutation.

#### Query / conversation

Load relevant state before the AI call. The model returns `proposed_updates = none` together with the response text.

#### Clarify

When intent genuinely cannot be resolved, the interaction returns no mutation plus a concise natural clarification.

---

### Request State and Client Presentation

Server lifecycle remains:

```text
accepted → processing → completed | failed
```

`completed` means canonical text is ready for the client. It does not mean optional TTS/audio preparation is finished.

For audio requests, the client initially shows Sending/Sent state and replaces the temporary user content with `user_message_text` on completion. For text requests, the exact submitted text remains visible.

---

### Result Persistence

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

### Response Audio Completion

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

### Result Retrieval and Audio Download

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

### Order / Talk Semantics

Order prioritizes unattended execution; Talk prioritizes conversational response. Both remain recorded asynchronous interactions rather than realtime media/WebRTC sessions.

---

### Upcoming Event Derivation and Mutation

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

### Direct Event Updates

Direct visible Daily Event edits remain deterministic/local-first: Room updates immediately, sync is queued, backend validates/reconciles, then Room receives canonical state. Upcoming Event edits target User Context and therefore follow the appropriate User Context mutation/reconciliation path rather than being persisted as Daily Event edits.

---

### Credits and TTS Resolution

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

### Reliability Rules

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

### Guiding Workflow

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

---

The request is marked `completed` and its completion push is sent before `SpeechService.generate` begins. Maintenance recovers pending or stale speech tasks after restart. A pending event audio request returns 404 until the authenticated Ogg/Opus asset is ready.
