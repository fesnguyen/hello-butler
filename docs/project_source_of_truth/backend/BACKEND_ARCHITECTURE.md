# Backend Architecture

**Version:** 2.0  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose and Pattern

The backend owns Butler intelligence, authoritative server state, asynchronous request processing, conversation persistence, planning, TTS, authentication, and synchronization.

```text
API → Application → Domain
          ↑
    Infrastructure
```

API owns transport/authentication. Application owns orchestration/transactions. Domain owns product rules. Infrastructure owns PostgreSQL, AI/TTS providers, push, and audio storage.

---

# One Butler Workflow

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

# Interaction Contract

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

# Request and Audio Lifecycle

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

# Shared TTS Service

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

# User/Profile, Credits, and User Context

Profile/settings persistence owns application configuration such as credits and TTS method. User Context owns Butler knowledge about the user's life. Do not create a generic settings table for current scope.

Credits are backend-authoritative Hello Butler product credits. The client cannot set its own balance. OpenAI TTS has an additional credit cost; Kokoro does not.

---

# Notes and Preferences

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

# Synchronization Contract

Backend state is authoritative. The client may cache a user-manageable notes/preferences projection in Room, but reconciliation must converge with User Context.

After relevant conversational mutations, the result/sync metadata should give the client enough information to trigger reconciliation without guessing from response prose. Direct settings mutations return enough stable identity/state for idempotent reconciliation.

Protected endpoints derive identity from verified authentication; client-provided `user_id` is never authority.

---

# Planning and Upcoming Events

Daily Plan / Daily Event lifecycle remains authoritative. Upcoming Events are derived projections of actionable User Context, not persisted domain entities.

Upcoming Events are one planning input among routines, preferences, constraints, existing events, and other context. Mutations resolve to underlying User Context and distinguish occurrence-level exceptions from recurring-rule changes.

Morning Brief and Good Night Summary use the shared TTS service. Ordinary Butler responses remain silent until client playback is selected.

---

# Conversation and Push

Backend conversation text is durable:

```text
role=user   → transcript/understood utterance OR submitted text
role=butler → canonical response text
```

Audio is temporary server data. FCM is a wake-up signal, not canonical content. Result fetch and audio download remain idempotent/retryable; push failure does not roll back a completed request.

---

# Guiding Rule

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


# Ecosystem boundary

FastAPI owns only Hello Butler and its database/Alembic history. Showcase owns its PostgreSQL access through its own server-side data layer in `web/showcase`. Future Admin will manage ecosystem databases through its trusted server-side layer. See [Showcase](../web/showcase/SHOWCASE.md).


## On-demand reminder speech

Reminder playback reuses the shared `SpeechService` and existing DailyEvent audio fields. `POST /api/sync/events/{event_id}/speech?version=N` validates authenticated ownership, planned status, reminder type, and version; an unavailable owner transitions to pending and schedules generation. Existing pending/processing/ready speech is reused. The normal maintenance scan recovers pending reminders, and `GET /api/sync/events/{event_id}/audio` serves ready reminder audio. Content falls back to description/title. Showing notifications alone never requests synthesis. No provider settings or tables change.
