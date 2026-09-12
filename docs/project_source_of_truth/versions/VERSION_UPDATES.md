# Source of Truth Version Updates

This file records coordinated version updates for documents under `docs/project_source_of_truth/`.

---

## Version 1.5 — 2026-09-12

### Scope

Version 1.5 refines the v1.4 async Butler architecture by separating input transport from Butler reasoning.

Updated to `1.5`:

- `PROJECT.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`
- `client/CLIENT_ARCHITECTURE.md`
- `client/CLIENT_WORKFLOW.md`
- `client/CLIENT_SYNC_FLOW.md`

`ENGINEERING.md` remains `1.4` because no engineering rule changed.

### Main change

The three client controls are now:

```text
Order → recorded audio
Talk  → recorded audio
Text  → direct typed/editable text
```

Text no longer records audio, uses local STT, sends audio for backend transcription, or waits for a server-generated editable draft.

### Backend architecture

There are two input ingress boundaries:

```text
Audio API → Order/Talk audio → transcription ┐
                                             ├→ normalized message → shared Butler workflow
Text API  → typed Text ──────────────────────┘
```

Both return an asynchronous request identity and use the same request/result lifecycle. Do not implement separate audio and text Butler graphs.

After normalization, both use the same context loading, semantic intent routing, deterministic actions, response text generation, response audio generation, persistence, completion FCM, and result retrieval.

Text is an input method, not a semantic intent. Typed Text normally uses the conversational/Talk expectation while semantic intent remains command/query/clarify based on message content.

### Client behavior

- Order/Talk retain hold-to-record and `Sending... → Sent → transcript` behavior.
- Text enables a normal editable composer inside the existing Butler conversation overlay.
- Text sends the exact submitted text; no transcript replacement is required.
- Audio and text requests share foreground/background completion, Room persistence, notification, and response-audio caching logic.
- `Open in App` opens the single Main Screen with the Butler conversation overlay visible; there is no dedicated conversation screen.
- Butler response messages remain compact: response text first, duration beside `Butler`, and playback icons directly below the response.

### Unchanged v1.4 behavior

- no realtime/WebRTC Butler voice conversation
- one canonical Butler response across app and notification
- FCM as wake-up/completion signal rather than canonical payload
- foreground Coroutine/Repository vs background WorkManager convergence
- immediate response-audio caching after completion
- speaker/private-listen playback actions
- `Open in App` on Butler notifications
- Morning Brief and Good Night Summary automatically Speak Aloud, allow immediate Stop, and retain Open in App
- client-side long-lived response-audio cache with limited backend audio retention
- instant app startup with interaction available before synchronization completes

---

## Version 1.4 — 2026-09-12

### Scope

Version 1.4 introduced the asynchronous recorded-audio Butler interaction model: Order/Talk record then upload audio, requests continue asynchronously, FCM wakes the client on completion, foreground/background paths converge through one repository, canonical results persist in Room, and Butler response audio is downloaded/cached locally.

It also established one canonical response across notification and in-app presentation, compact speaker/private-listen actions, Open in App, limited backend audio retention, instant startup, and automatic Speak Aloud with Stop for Morning Brief and Good Night Summary.

Version 1.5 supersedes v1.4's temporary assumption that Text also used recorded audio.

---

## Versioning Rule

When a coordinated architecture/product change modifies multiple source-of-truth documents, update the affected documents to the same release version and record the change here.

Documents whose rules did not change do not require a version bump solely to match unrelated architecture documents.
