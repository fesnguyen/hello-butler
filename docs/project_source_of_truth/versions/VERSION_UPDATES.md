# Source of Truth Version Updates

This file records coordinated version updates for documents under `docs/project_source_of_truth/`.

---

## Version 1.6 — 2026-09-13

### Scope

Version 1.6 corrects the backend AI-processing contract for Order/Talk.

Updated to `1.6`:

- `PROJECT.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`

Client transport, Room, WorkManager, FCM, notification, and UI behavior from v1.5 remain unchanged.

### Corrected backend behavior

The v1.5 docs incorrectly described the normal Order/Talk AI path as effectively:

```text
recorded audio
   ↓
standalone transcription
   ↓
transcript-only Butler reasoning
   ↓
response text
   ↓
separate response-audio generation
```

Version 1.6 replaces that with the intended multimodal contract:

```text
original Order/Talk audio
+
textual Butler context
├── instructions
├── interaction mode
├── recent conversation
├── User Context / preferences
├── relevant plan/events
└── now / timezone
        ↓
audio-capable multimodal Butler model / provider boundary
        ↓
canonical result
├── transcript / understood utterance text
├── structured intent/action information when needed
├── Butler response text
└── Butler response audio
```

The original audio must remain available through the semantic reasoning boundary. A transcript is still required for history/UI, but it must not be the sole semantic input to a text-only Butler model.

### Response delivery

The asynchronous lifecycle remains:

```text
202 + request_id
   ↓
backend processing
   ↓
persist canonical text/result metadata
   ↓
save response audio temporarily on backend
   ↓
FCM completed(request_id)
   ↓
client fetches result text/metadata
   ↓
client immediately downloads response audio
   ↓
Room / local audio cache
```

FCM remains a wake-up signal, not the audio transport. Backend audio remains retention-limited; client local cache remains the normal long-lived audio copy.

### Unchanged behavior

- Order/Talk are recorded, not realtime-streamed, interactions.
- Text is direct typed/editable input with no STT.
- Audio and text share one Butler product/domain behavior and one async request/result lifecycle.
- Deterministic application/domain code remains authoritative for mutations.
- One canonical Butler message is used for both in-app and notification presentation.
- Foreground Coroutine/Repository and background WorkManager converge through the same result-fetch path.
- Morning Brief and Good Night Summary keep automatic Speak Aloud + Stop + Open in App behavior.

---

## Version 1.5 — 2026-09-12

- Corrected the development guide's manual example to use the v1.5 asynchronous request/result endpoints.

### Scope

Version 1.5 refined the v1.4 async Butler architecture by separating input transport from Butler reasoning and changing Text to direct typed/editable input.

Updated to `1.5`:

- `PROJECT.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`
- `client/CLIENT_ARCHITECTURE.md`
- `client/CLIENT_WORKFLOW.md`
- `client/CLIENT_SYNC_FLOW.md`

`ENGINEERING.md` remained `1.4` because no engineering rule changed.

The v1.5 client/input model remains valid:

```text
Order → recorded audio
Talk  → recorded audio
Text  → direct typed/editable text
```

Version 1.6 supersedes only v1.5's transcript-first backend AI-processing description.

---

## Version 1.4 — 2026-09-12

Version 1.4 introduced the asynchronous recorded-audio Butler interaction model: Order/Talk record then upload audio, requests continue asynchronously, FCM wakes the client on completion, foreground/background paths converge through one repository, canonical results persist in Room, and Butler response audio is downloaded/cached locally.

It also established one canonical response across notification and in-app presentation, compact speaker/private-listen actions, Open in App, limited backend audio retention, instant startup, and automatic Speak Aloud with Stop for Morning Brief and Good Night Summary.

---

## Versioning Rule

When a coordinated architecture/product change modifies multiple source-of-truth documents, update the affected documents to the same release version and record the change here.

Documents whose rules did not change do not require a version bump solely to match unrelated architecture documents.
