# Backend Architecture

**Version:** 1.9  
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

API owns transport/authentication. Application owns orchestration and transaction boundaries. Domain owns product concepts/rules. Infrastructure owns PostgreSQL, the multimodal AI provider, TTS providers, push, audio storage, and other integrations.

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
     interaction AI call
                  ↓
       complete AI result
                  ↓
       validate/apply changes
                  ↓
       canonical text result
                  ↓
       asynchronous TTS
```

Do not create separate Butler brains for audio and text. Order/Talk preserves the original recorded audio through the interaction call. Text uses the same product behavior with typed text as input.

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

# Interaction AI Contract

For every normal Butler interaction, the backend gathers enough authoritative context before invoking the AI provider so one interaction call can understand the input, propose supported mutations, and finalize canonical response text.

For Order/Talk the request contains the original recorded audio plus relevant textual context: Butler instructions, interaction mode, recent relevant conversation, User Context/preferences, relevant Daily Plan/Events, identifiers required for mutation, and current time/timezone. Text uses typed text instead of recorded audio.

The interaction call is responsible for understanding/reasoning, not speech synthesis. TTS is a separate provider boundary invoked after canonical response text exists. TTS must not reason, rewrite, or paraphrase the response.

---

# AI Result Contract

The interaction AI result contains:

```text
AI result
├── user_message_text
│   ├── audio source → transcript / understood utterance text
│   └── text source  → submitted text
├── proposed_updates
│   ├── Daily Event add / update / remove / skip / other supported mutation
│   ├── User Context / preference change
│   └── none when no state change is required
└── Butler response text
```

Application/domain code remains authoritative for state: it validates supported updates and performs persistence. The AI must not write directly to PostgreSQL.

---

# Request Lifecycle

Both transports share durable states:

```text
accepted
processing
completed
failed
```

`completed` means the canonical Butler text result is ready. Optional response audio may still be preparing.

Normal processing is:

```text
accepted request
      ↓
load original input + relevant context
      ↓
interaction AI call
      ↓
receive transcript + proposed updates + response text
      ↓
validate/apply supported state changes
      ↓
persist canonical conversation/result metadata
      ↓
mark completed + notify client
      ↓
client can show text
      │
      └── TTS continues asynchronously
              ↓
          encode/store audio
              ↓
          audio becomes ready
```

TTS failure must not turn an already completed Butler interaction into a failed request.

---

# Mutation Semantics

The AI call must have enough preloaded state to propose the intended mutation without requiring another reasoning round trip after execution.

The backend validates the proposal before applying it. If a proposed mutation is invalid or cannot safely be applied, normal software error/reconciliation behavior handles that condition.

Important mutations remain idempotent where retry is possible.

---

# Canonical Result Contract

A completed request conceptually contains:

```text
request_id
input_source
user_message_text
Butler response text
response audio state/reference when available
changed entity metadata when relevant
completion timestamp
```

One completed request produces one user conversation message and one Butler conversation message. Notification and in-app presentation are surfaces for that same result.

---

# Shared Response Audio Service

All Butler speech uses the same backend TTS service:

```text
Conversation response ─┐
Morning Brief ─────────┼──> shared TTS service
Good Night Summary ────┘
                              ↓
                     User/Profile.tts_method
                      ├── OPEN_SOURCE → Kokoro
                      └── OPENAI
                            ├── credits > 0 → OpenAI TTS
                            └── credits <= 0 → Kokoro
```

Do not duplicate TTS provider selection, credit handling, encoding, or storage rules in conversation or planning code.

The runtime fallback must not modify the stored `OPENAI` preference. Provider-specific implementation remains in Infrastructure behind the TTS boundary; application orchestration selects the provider and owns credit policy.

Both providers must produce audio compatible with the existing backend/client audio transport contract. Generated audio is stored temporarily, exposed through the authenticated audio path, and cached by the client.

Response audio has an independent lifecycle such as `pending → processing → ready | unavailable`. The exact persistence shape may reuse existing request/event metadata; a new table is not required by this decision.

---

# User Settings and Credits

The client-facing destination is named **User Settings**. Do not create a separate UserConfig/settings table for the current scope. Extend the existing user/profile persistence and API model with the credit balance and TTS preference. Profile settings are distinct from User Context: User Context describes the user's life and planning context, while Profile controls application behavior.

Credits are Hello Butler product credits, not raw provider token counts. Backend application logic is authoritative for checking and deducting credits. Paid Butler reasoning/planning requires sufficient credits. OpenAI TTS has an additional credit cost; Kokoro/open-source TTS has no additional credit cost. Credit updates must be server-controlled and must not allow the balance to become negative.

User Settings also exposes user-manageable saved Butler preferences using the existing authoritative preference/User Context persistence. The API must support listing the relevant saved preferences and deleting them individually. Deletion removes/updates the authoritative record; do not create a duplicate client-preference store. Only preference records intended for user management are exposed through this list—do not automatically expose routines, temporary/one-time planning context, upcoming-event context, or internal metadata.

The normal client user/profile contract may read credits and read/update `tts_method`, but must not permit the client to arbitrarily set its own credit balance.

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

FCM is a wake-up signal, not canonical content. Result fetch and authenticated audio download remain idempotent/retryable. Push failure must not roll back a completed request. Audio readiness is reconciled separately so the client can receive text before speech is ready.

---

# Planning, Upcoming Events, and Proactive Speech

The Daily Plan / Daily Event lifecycle remains authoritative.

Upcoming Events are derived projections of actionable User Context, not persisted domain entities. **Do not add an `UpcomingEvent` table/model.** User Context remains authoritative; derive the current/future Upcoming representation when needed for planning or presentation.

Upcoming Events are one planning input among routines, preferences, constraints, existing events, and other relevant User Context. They are not future Daily Plans. Planning may use an Upcoming Event without materializing it as a Daily Event. Daily routines are recurring planning context and are not Upcoming Events.

Only context whose applicable time range is active today or in the future is eligible for Upcoming presentation. For example, "Diet for a week starting September 22" may project an Upcoming Event spanning September 22–28 without requiring a generic Diet Daily Event on every day.

Upcoming Event mutations resolve to the underlying User Context. Update/reschedule/skip/remove must preserve a single source of truth and distinguish occurrence-level exceptions from changes/removal of an entire recurring rule.

Morning Brief and Good Night Summary use the same shared backend TTS service and the user's TTS method. Their backend-generated audio should be available for the client to cache before scheduled playback when practical. Ordinary Butler responses remain silent by default until the user selects playback.

---

# Authentication and Persistence

Protected endpoints derive identity from verified authentication; client-provided `user_id` is never authority.

PostgreSQL remains authoritative for users/profile data including credits and TTS preference, User Context, conversation text, Daily Plans/Events, request/result metadata, devices, and synchronization metadata. Temporary audio assets may live outside PostgreSQL with referenced metadata.

---

# Guiding Rule

```text
Rich input + complete relevant context
              ↓
       interaction AI call
              ↓
 transcript + proposed updates
 + canonical response text
              ↓
 validate/apply → persist → deliver text
              ↓
       asynchronous shared TTS
```

Canonical text delivery must not wait for optional speech generation.