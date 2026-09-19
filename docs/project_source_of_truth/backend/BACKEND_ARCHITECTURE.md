# Backend Architecture

**Version:** 1.7  
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

API owns transport/authentication. Application owns orchestration and transaction boundaries. Domain owns product concepts/rules. Infrastructure owns PostgreSQL, the multimodal AI provider, push, audio storage, and other integrations.

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
       load complete context
                  ↓
     ONE multimodal AI call
                  ↓
       complete AI result
                  ↓
       validate/apply changes
                  ↓
       canonical result
          text + audio
```

Do not create separate Butler brains for audio and text. Order/Talk preserves the original recorded audio through the single multimodal call. Text uses the same product behavior with typed text as input.

---

# Primary API Boundaries

Conceptually:

```text
POST /api/butler/requests/audio
POST /api/butler/requests/text
GET  /api/butler/requests/{request_id}
GET  /api/butler/requests/{request_id}/audio
```

Both input endpoints return quickly with `202 Accepted` and a stable `request_id`, then continue through the same asynchronous request/result lifecycle.

---

# Single-Call Multimodal Contract

For every normal Butler interaction, the backend must gather enough authoritative context before invoking the AI provider so that one multimodal API request can produce the complete proposed interaction result.

For Order/Talk the request contains:

```text
original recorded audio
+
complete relevant textual context
├── Butler instructions
├── interaction mode
├── newest relevant conversation
├── User Context / preferences
├── current/relevant Daily Plan and Events
├── identifiers/details required to propose mutations
└── now / timezone
```

For Text, typed text replaces the recorded audio while the same relevant Butler context is supplied.

The normal request path must make **one OpenAI/multimodal provider API call per Butler interaction**. Do not split normal processing into separate transcription, reasoning, and response-audio generation calls.

---

# AI Result Contract

The single multimodal call returns one complete proposed result:

```text
AI result
├── user_message_text
│   ├── audio source → transcript / understood utterance text
│   └── text source  → submitted text
├── proposed_updates
│   ├── Daily Event add / update / remove / skip / other supported mutation
│   ├── User Context / preference change
│   └── none when no state change is required
├── Butler response text
└── Butler response audio matching that response text
```

Response text and response audio are two representations of the same Butler message. The provider contract must require the spoken audio to match the returned canonical response text rather than independently elaborating or changing the answer.

The model is trusted to understand the supplied context and propose the complete interaction in one call. Application/domain code remains authoritative for state: it validates supported updates and performs persistence. The AI must not write directly to PostgreSQL.

This design deliberately accepts greater reliance on the multimodal model in exchange for lower latency, lower provider-call overhead/cost, and removal of semantic drift between a separately generated response and response audio.

---

# Request Lifecycle

Both transports share durable states:

```text
accepted
processing
completed
failed
```

Normal processing is:

```text
accepted request
      ↓
load original input
      ↓
load complete relevant Butler context
      ↓
ONE multimodal API call
      ↓
receive transcript + proposed updates + response text + matching audio
      ↓
validate proposed updates
      ↓
apply/persist supported state changes
      ↓
normalize/store returned response audio when required by its transport format
      ↓
persist canonical conversation/result metadata
      ↓
mark completed
      ↓
FCM completed(request_id)
```

There is no second AI call after domain mutation to rewrite or synthesize the response.

---

# Mutation Semantics

The single AI call must have enough preloaded state to propose the intended mutation without requiring an AI round trip after execution.

The backend validates the proposal before applying it. If a proposed mutation is invalid or cannot safely be applied, normal software error/reconciliation behavior handles that condition; the backend must not silently invent a different AI response through a second provider call.

Important mutations remain idempotent where retry is possible.

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

One completed request produces one user conversation message and one Butler conversation message. Notification and in-app presentation are surfaces for that same result.

---

# Response Audio Storage and Delivery

Response audio comes from the same multimodal API call as the canonical response text.

```text
ONE AI call returns response text + matching audio
      ↓
backend validates/persists result
backend normalizes/stores response audio asset
      ↓
FCM completed(request_id)
      ↓
client GETs canonical result text/metadata
      ↓
client immediately downloads response audio
      ↓
local cache
```

The backend may normalize/finalize the returned audio container for reliable playback. Audio normalization is media processing, not another AI generation call, and must not alter spoken content.

The text result must remain available even if audio download is delayed. Backend response audio is retention-limited.

---

# Conversation Persistence

Backend conversation text is durable:

```text
role=user   → transcript/understood utterance OR submitted typed text
role=butler → canonical response text
```

Audio is not the permanent server conversation record.

---

# Push and Result Fetch

FCM is a wake-up signal, not canonical content. Result fetch and authenticated audio download remain idempotent/retryable. Push failure must not roll back a completed request.

---

# Planning, Upcoming Events, and Proactive Speech

The Daily Plan / Daily Event lifecycle remains authoritative.

Upcoming Events are derived projections of actionable User Context, not persisted domain entities. **Do not add an `UpcomingEvent` table/model.** User Context remains authoritative; derive the current/future Upcoming representation when needed for planning or presentation.

Upcoming Events are one planning input among routines, preferences, constraints, existing events, and other relevant User Context. They are not future Daily Plans. Planning may use an Upcoming Event without materializing it as a Daily Event. Daily routines are recurring planning context and are not Upcoming Events.

Only context whose applicable time range is active today or in the future is eligible for Upcoming presentation. For example, "Diet for a week starting September 22" may project an Upcoming Event spanning September 22–28 without requiring a generic Diet Daily Event on every day.

Upcoming Event mutations resolve to the underlying User Context. Update/reschedule/skip/remove must preserve a single source of truth and distinguish occurrence-level exceptions from changes/removal of an entire recurring rule.

Morning Brief and Good Night Summary remain proactive-speech exceptions at the client presentation layer. Ordinary Butler responses remain silent by default until the user selects playback.

---

# Authentication and Persistence

Protected endpoints derive identity from verified authentication; client-provided `user_id` is never authority.

PostgreSQL remains authoritative for users, User Context, conversation text, Daily Plans/Events, request/result metadata, devices, and synchronization metadata. Temporary audio assets may live outside PostgreSQL with referenced metadata.

---

# Guiding Rule

```text
Rich input + complete relevant context
              ↓
      ONE multimodal AI call
              ↓
 transcript + proposed updates
 + canonical response text
 + matching response audio
              ↓
 validate/apply → persist → deliver
```

One Butler interaction should normally require one multimodal provider call.