# Backend Audio Interaction Architecture

**Status:** Proposed for review  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `../AUDIO_INTERACTION.md`

---

# Purpose

This document defines the backend responsibilities for Hello Butler's recorded push-to-talk interaction model.

The backend accepts completed audio recordings, uses an audio-capable AI provider to understand them, executes normal Butler application/domain logic, and produces durable Butler results containing canonical text and optional generated audio.

This is not a realtime streaming architecture.

---

# Architectural Boundary

```text
Android client
    │
    │ completed recording / typed text
    ▼
API layer
    ▼
Application use case
    ├── construct Butler context
    ├── invoke AI provider
    ├── execute domain action when required
    ├── persist conversation/result
    └── create response audio asset
    ▼
Repositories / providers
    ├── PostgreSQL
    ├── AI provider
    ├── audio asset store
    └── push provider
```

The backend remains authoritative for:

- Butler reasoning orchestration
- User Context
- conversation history
- Daily Plans and Daily Events
- domain state changes
- clarification decisions
- response persistence
- response-audio generation/association
- background result delivery

The client owns local recording, local queueing, playback routing, UI presentation, local cache, and notification interaction.

---

# Primary Conversational Boundary

The product should keep one Butler interaction concept rather than introducing a separate realtime subsystem.

`/api/butler/talk` remains the conceptual conversational boundary.

It must evolve so a Butler interaction can be submitted from either:

```text
text payload
recorded audio payload
```

The exact HTTP encoding may evolve. For audio, multipart upload or an upload-reference mechanism are both acceptable implementation choices.

Do not introduce `/api/butler/realtime/session` for the recorded push-to-talk flow.

---

# Interaction Modes

The backend must understand the three product expectations:

```text
order
talk
text
```

These are interaction expectations, not immutable semantic intents.

## Order

Order permits unattended completion.

```text
audio
  ↓
understand actual request
  ↓
execute / answer / clarify
  ↓
persist result
  ↓
produce canonical response text
  ↓
produce/associate response audio
  ↓
deliver foreground or push later
```

The HTTP connection is not the lifetime of the result. If the user leaves, the resulting Butler response remains durable.

## Talk

Talk uses the same recorded-audio request path but prioritizes low-latency completion because the user is expected to be present.

The backend should return synchronously when practical. If processing outlives the foreground interaction, the response must still become a durable result and be deliverable later.

## Text

Text mode has two product stages:

```text
recorded audio
   ↓
prepare editable text
   ↓
client review/edit
   ↓
explicit send
   ↓
normal Butler reasoning
```

The preparation stage may use:

- speech content
- user language preferences
- recent conversation
- User Context when relevant
- translation/normalization rules

The preparation stage must not execute a Daily Event/User Context mutation merely because the spoken draft describes one.

Only the explicit send of the reviewed text authorizes normal Butler processing.

The implementation may represent these stages with a request subtype/stage field or separate application use cases behind the same product surface. The docs intentionally do not freeze the wire schema yet.

---

# AI Provider Integration

The infrastructure AI provider abstraction should support audio input without forcing the application/domain layers to know provider-specific request formats.

Conceptually:

```text
ButlerAudioRequest
├── recording/reference
├── interaction expectation
├── current time + timezone
├── relevant conversation
├── User Context
├── relevant Daily Events
└── user preferences
        ↓
Audio-capable AI provider
        ↓
structured Butler decision/result
+ canonical response text
```

The application layer remains responsible for applying any returned action through existing services/repositories.

The AI provider must not receive direct database write capability.

Where the provider supports speech output directly, the infrastructure layer may request an audio rendition of the canonical Butler response. Where response speech requires a second provider call, that remains an infrastructure detail.

The application contract is:

```text
canonical response text
+
optional response audio asset
```

not a particular OpenAI endpoint/model name.

---

# Structured Decision and Human Response

Audio understanding must not weaken the existing structured-action boundary.

For Order/Talk, the AI layer should still resolve the user utterance into application-level outcomes such as:

```text
create_daily_event
update_daily_event
skip_daily_event
remember_user_context
answer_today_events
none / clarify
```

The exact action set may evolve with the existing Butler decision model.

A final result may independently include:

```text
domain mutation
conversation persistence
clarification requirement
canonical response text
response audio
```

The spoken response must agree with the persisted action result.

For example, do not generate audio saying an event was moved until the application layer confirms that the update succeeded.

---

# Request Processing

Recommended Order/Talk lifecycle:

```text
1. authenticate user
2. validate interaction metadata and audio limits
3. persist/hold input only as required for processing
4. load relevant Butler context
5. invoke audio-capable reasoning provider
6. validate structured result
7. execute application/domain operation
8. derive final canonical response text from confirmed outcome
9. persist normalized user turn + Butler response
10. generate/attach response audio
11. mark response deliverable
12. return directly when caller is present
13. otherwise/also notify client as required
14. release transient input recording
```

Failures after a domain mutation must not cause the system to lose the authoritative result merely because response-audio generation failed.

Text is the fallback canonical representation.

---

# Durable Butler Response

The backend needs a durable concept representing a completed or pending Butler communication.

The exact database design is intentionally deferred, but it must support at least:

```text
response id
user id
canonical text
created timestamp
conversation relationship
interaction/result type
clarification/pending status when relevant
audio asset reference when available
delivery/read state as needed
```

This may be implemented by extending ConversationHistory, adding response metadata, or introducing a dedicated result model. The implementation should choose the smallest design that supports the required lifecycle without duplicating conversation state.

The requirement is behavioral: a response must survive the originating HTTP request and remain retrievable later.

---

# Input Audio Lifecycle

User recordings contain sensitive voice data.

By default:

```text
receive/queue recording
    ↓
process
    ↓
persist normalized textual representation when appropriate
    ↓
delete transient server recording
```

Do not retain raw user audio indefinitely as conversation history.

Temporary persistence is allowed where needed for:

- retries
- provider upload
- asynchronous processing
- crash recovery

Retention must be bounded and documented in implementation configuration.

---

# Response Audio Lifecycle

Butler audio has a different requirement because the user may listen minutes later from a notification or conversation history.

```text
canonical response text
    ↓
generate speech
    ↓
store authorized audio asset
    ↓
associate with Butler response
    ↓
client fetch/cache/play
    ↓
expire by retention policy
```

Domain entities should store an opaque audio reference/identifier, not vendor URLs that become permanent domain contracts.

A provider/storage abstraction should allow implementation to move between local development storage and production object storage without changing Butler application logic.

Do not put large audio blobs in push notification payloads.

---

# Foreground Response Delivery

When the caller remains active, the backend may return the final result directly.

Conceptually:

```text
{
  response_id,
  text,
  audio_reference,
  result_state,
  synchronized_changes
}
```

This is illustrative only; final API schema belongs to implementation design.

The client can append the response immediately and request/cache audio.

---

# Background Result Delivery

Order must not rely on the initiating app process remaining alive.

When a durable response becomes available and the client is not actively consuming it:

```text
backend result
    ↓
persist response
    ↓
push provider
    ↓
compact push: response id + preview + type
    ↓
client notification
    ↓
client fetches authorized full result/audio as needed
```

Push should be treated as a wake-up/notification signal, not authoritative storage.

If push delivery fails, the response remains available through normal synchronization when the app next opens.

---

# Clarification

Clarification is itself a durable Butler response.

Example:

```text
User audio is unclear or ambiguous
    ↓
Butler: "Sorry, what did you mean, sir?"
    ↓
text persisted
+ audio available
+ response delivered
```

The next user recording should use recent conversation to resolve the immediate clarification turn without allowing stale conversation to override a newer explicit request.

---

# Text Butler Preparation

Text preparation should be a distinct application behavior from command execution.

Input:

```text
recorded audio
+ language/user preferences
+ narrowly relevant recent conversation
```

Output:

```text
editable text
```

Possible transformations:

- transcription
- punctuation
- light normalization
- correction of obvious ASR ambiguity using context
- translation
- natural message wording

It must not silently add factual content the user did not express.

The returned draft should be safe to edit before explicit send.

---

# Startup Independence

The backend cannot make the Android app wait for Butler initialization before recording.

Therefore the backend API must support delayed submission of locally captured requests.

A queued request may arrive after:

- app startup synchronization
- token refresh
- connectivity recovery
- process restart

Request handling should be idempotent where retries are possible. The client should have a request identifier sufficient to prevent accidental duplicate execution of the same queued Order.

---

# Reliability Rules

1. Never execute the same queued Order twice because an upload/retry is repeated.
2. Persist authoritative domain changes transactionally according to existing repository/application rules.
3. Do not report success in text/audio until the mutation has succeeded.
4. A speech-generation failure must not erase an otherwise valid textual result.
5. A push-delivery failure must not erase a durable response.
6. A client disconnect must not cancel a valid unattended Order unless the user explicitly cancels it.
7. Raw user audio should not become permanent storage by accident.
8. Audio-provider outages should degrade to textual result delivery where possible.

---

# Security

- Keep provider API keys server-side.
- Authenticate every audio upload and audio-asset fetch.
- Enforce size/duration/content-type limits before provider calls.
- Use short-lived authorized asset access or authenticated fetch endpoints.
- Avoid exposing stable public audio URLs.
- Do not log raw audio payloads.
- Apply the same user-ownership checks to audio assets as conversation history.

---

# Explicitly Rejected Architecture

The normal Butler path is not:

```text
Android ⇄ WebRTC ⇄ OpenAI realtime session
```

and is not:

```text
Android microphone stream
    ↓
FastAPI continuous relay
    ↓
OpenAI continuous stream
```

The required path is:

```text
Android completed recording
    ↓
Hello Butler backend
    ↓
audio-capable AI reasoning
    ↓
existing application/domain logic
    ↓
durable text + audio result
```

A future live voice mode would require a separate architecture decision.
