# Hello Butler Recorded Audio Interaction

**Status:** Proposed source-of-truth extension for review  
**Scope:** Voice input, Text Butler preparation, Butler response media, background delivery, and startup responsiveness

---

# Purpose

Hello Butler does not use a live voice-chat model for normal interaction.

The primary voice interaction is a recorded push-to-talk workflow:

```text
press and hold
    ↓
record locally
    ↓
release
    ↓
upload the completed recording
    ↓
backend + AI understand the request
    ↓
execute / answer / clarify / prepare editable text
    ↓
produce durable result
    ↓
text + audio presentation
```

There is no continuously connected conversation session, no WebRTC requirement, and no `/api/butler/realtime/session` product concept for this flow.

The interaction should feel fast and natural without requiring the user to stay in the app while Butler works.

---

# Core Principles

The following rules define the interaction model.

1. Voice input is recorded locally and sent after the user releases the hold-to-talk control.
2. The backend receives audio directly; Android speech recognition is not the normal conversational interpretation path.
3. OpenAI or another configured multimodal provider may understand speech directly from the recorded audio.
4. The backend remains authoritative for Butler reasoning, actions, persistence, user context, and Daily Events.
5. The AI provider does not directly mutate the database. Application/domain logic performs all state changes.
6. Final Butler responses are durable and have a canonical text representation plus an audio representation when speech playback is supported.
7. Raw user recordings are transient input and are not retained by default after successful processing.
8. Generated Butler audio may be retained as a response asset long enough for later playback and background delivery.
9. Push notifications carry identifiers and compact text metadata, not large audio payloads.
10. The user must be able to begin recording immediately after app launch without waiting for plan/history synchronization.
11. If network or authentication refresh is temporarily unavailable, recording should still be captured locally and queued for upload whenever safe.

---

# Interaction Modes

The product retains three user-facing interaction expectations:

```text
Order Butler
Talk to Butler
Text Butler
```

The modes describe the user's expected experience, but the content of the request still determines its actual meaning.

## Order Butler

Order is optimized for requests that Butler can handle while the user leaves immediately.

```text
hold
  ↓
speak
  ↓
release
  ↓
upload audio
  ↓
understand request
  ↓
execute / answer / clarify
  ↓
persist result
  ↓
produce response text + audio
  ↓
deliver now or later
```

Example:

> "Move my meeting from three to four."

The user may lock the phone or leave the app after releasing the button. When processing finishes, the result remains available through conversation history and, when appropriate, a push notification.

Order does not forbid clarification. If the recording is unclear or important information cannot be inferred safely, Butler should respond naturally with a clarification instead of inventing an action.

## Talk to Butler

Talk uses the same record-then-send transport as Order.

The difference is expectation: the user is currently present and normally wants the result as soon as possible.

```text
hold
  ↓
speak
  ↓
release
  ↓
upload audio
  ↓
understand request
  ↓
answer / execute / clarify
  ↓
response text + audio
```

Talk is not a live full-duplex conversation. Every turn is a completed recording followed by a Butler result.

If the user leaves before the response arrives, the result becomes a normal durable background result rather than being lost.

## Text Butler

Text Butler changes meaning from the previous local-STT design.

The user still speaks using hold-to-talk, but the backend uses the recorded audio, User Context, user preferences, and relevant recent conversation to produce editable text.

```text
hold
  ↓
speak
  ↓
release
  ↓
upload audio
  ↓
understand wording and context
  ↓
transcribe / normalize / translate as appropriate
  ↓
return editable text
  ↓
user reviews and edits
  ↓
explicit send
```

Text preparation is not itself permission to execute a domain action.

Before explicit send, Butler may:

- transcribe speech
- normalize obvious speech-recognition ambiguity
- apply the user's language preference
- translate when the spoken intent clearly asks for or implies translation
- use recent conversation to resolve pronouns or missing local context
- produce natural editable wording

The user remains in control of the final text.

After explicit send, the submitted text enters normal Butler reasoning and may create/update state, answer a question, or require clarification.

---

# Audio Input

The Android client records audio locally while the user holds the interaction control.

The normal request boundary begins after release. The client sends a completed compressed recording instead of streaming an open microphone to the server.

Preferred properties:

- mono speech audio
- efficient compressed format
- optimized for human voice rather than music
- no raw PCM upload unless required for compatibility
- recording duration and payload limits enforced by the client and backend

Opus should be preferred where Android and provider compatibility make it practical, but the product contract must not depend on a single codec.

The server may transcode at the infrastructure boundary if the configured AI provider requires a different accepted format.

---

# Butler Response Model

A Butler result is durable application data, not merely an HTTP response body.

Conceptually:

```text
Butler Response
├── id
├── canonical text
├── audio asset/reference when available
├── interaction/result type
├── clarification state when applicable
├── related domain changes when applicable
├── created_at
└── delivery state
```

The exact persistence schema may evolve, but the product behavior must support the user returning later and receiving the same result.

Canonical text is the authoritative human-readable representation of what Butler communicated.

Generated audio is a presentation asset for that response. It must represent the same Butler message rather than independently inventing different wording.

---

# Response Audio

Conversational responses should use provider-generated speech rather than Android Text-to-Speech when server audio is available.

This enables a consistent Butler voice and allows the user to replay the original response later.

Local Android TTS remains useful for offline execution, prepared reminders, fallback behavior, and any already-synchronized content that must remain usable without Internet access.

The response-audio lifecycle is different from user-input audio:

```text
user recording
    ↓
process
    ↓
discard by default after successful processing

Butler generated audio
    ↓
associate with durable response
    ↓
make retrievable to authorized client
    ↓
expire according to retention policy when no longer needed
```

The implementation should use an audio-asset abstraction rather than making domain logic depend on local files, database blobs, or a specific cloud object-storage vendor.

---

# Foreground Delivery

If the app is open when a Butler result becomes available:

```text
backend result
    ↓
client receives/synchronizes result
    ↓
append to conversation history
    ↓
show canonical text
    ↓
show playback action when audio exists
```

The user decides whether to listen unless the current product setting explicitly requests automatic speech.

The response should remain available after temporary overlays disappear.

---

# Background and Idle Delivery

Order and other delayed results must work when the app is not in the foreground.

The server sends a push notification when a meaningful pending Butler response becomes available.

The push payload should contain compact delivery information such as:

```text
response id
short text preview
response type
safe notification metadata
```

It should not contain the full audio asset.

The Android client retrieves and caches authorized response data/audio as needed.

The intended notification experience may expose actions such as:

```text
Listen privately
Speak aloud
Read / Open
```

Product meaning:

- **Listen privately**: play Butler through a private/earpiece-oriented route where Android supports it, so the user can hold the phone to the ear.
- **Speak aloud**: play through the normal loudspeaker route.
- **Read / Open**: expand available text or open the app to the corresponding conversation/result.

The implementation must respect Android notification, background-execution, audio-focus, and device-routing constraints rather than pretending the notification is an actual telephone call.

---

# Instant Startup Requirement

Opening Hello Butler must not block voice capture on remote initialization.

The critical startup path is:

```text
tap app icon
    ↓
render usable shell
    ↓
initialize local recorder / interaction controls
    ↓
user can hold and speak
```

The following work belongs off the critical path whenever possible:

```text
refresh today's plan
load older conversation
sync Room with server
fetch pending responses
refresh non-blocking content
generate previews
other network-dependent initialization
```

The client may hydrate the screen progressively from local storage while network synchronization continues.

After microphone permission has already been granted, the user should not need to wait for backend/OpenAI initialization before recording.

If the app cannot upload immediately:

```text
record locally
    ↓
release
    ↓
persist queued request safely
    ↓
upload when connectivity/auth is ready
```

This requirement is central to the product experience: Butler should be available to capture a thought at the moment the user opens the app.

---

# Offline Behavior

AI reasoning still requires server connectivity, but capturing a voice request does not need to.

Offline flow:

```text
user records request
    ↓
client stores queued audio request
    ↓
UI acknowledges queued state
    ↓
connectivity returns
    ↓
upload
    ↓
backend processes
    ↓
response synchronizes/pushes back
```

Queued recordings must be protected as user data and removed locally after successful upload according to the queue lifecycle.

Already-synchronized Daily Events, notifications, prepared speech, and local event management continue to follow the existing offline-first product rules.

---

# Conversation History

The conversation history should remain text-oriented for searchability, compact storage, context construction, debugging, and synchronization.

For a processed voice turn the backend may persist a normalized textual representation of the user's utterance plus Butler's canonical response text.

Raw user recordings are not conversation history by default.

Butler response audio is an associated presentation asset, not the sole historical record.

Text Butler preparation should not be treated as an executed user instruction until the user explicitly sends the editable text.

---

# Security and Privacy

- Permanent OpenAI/provider credentials remain on the backend.
- Clients authenticate to Hello Butler, not directly with privileged provider credentials.
- User-input recordings should be retained only as long as required for queueing/processing unless the product later introduces explicit retention features.
- Response audio must be access-controlled like the corresponding conversation/result.
- Push payloads should avoid sensitive full-content exposure beyond the user's notification preferences.
- Logs must not contain raw audio or provider credentials.

---

# Explicit Non-Goals

This design does not introduce:

- continuous microphone streaming
- full-duplex ChatGPT-style live voice chat
- WebRTC as a product requirement
- a persistent OpenAI Realtime session
- `/api/butler/realtime/session`
- direct AI-provider access to PostgreSQL
- mandatory local Android STT for Butler voice commands
- mandatory local Android TTS for conversational Butler responses

A future live-conversation feature would be a separate product decision and must not complicate this recorded push-to-talk architecture prematurely.

---

# Relationship to Existing Source-of-Truth Docs

This review document intentionally changes voice-specific assumptions currently present in `PROJECT.md`, `backend/BACKEND_ARCHITECTURE.md`, `backend/BACKEND_WORKFLOW.md`, `client/CLIENT_ARCHITECTURE.md`, `client/CLIENT_WORKFLOW.md`, and `client/CLIENT_SYNC_FLOW.md`.

In particular, it supersedes voice-specific statements that define Text Butler as local STT, define normal conversational speech as client-owned STT/TTS, or assume Butler results exist only while the initiating request is open.

During review, this document and the companion backend/client audio documents describe the proposed replacement behavior. Before implementation begins, approved rules should be folded into the canonical sections of the existing source-of-truth documents so no contradictory guidance remains.
