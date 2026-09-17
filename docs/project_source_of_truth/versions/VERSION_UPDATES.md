# Source of Truth Version Updates

This file records coordinated version updates for documents under `docs/project_source_of_truth/`.

---

## Version 1.7 — 2026-09-17

### Scope

Version 1.7 establishes a true single-call multimodal provider workflow for normal Butler interactions.

Updated to `1.7`:

- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`

`PROJECT.md` v1.6 already defines the complete multimodal result contract—transcript/understood utterance, structured action information, Butler response text, and response audio—and remains authoritative for product behavior. Version 1.7 makes the provider-call constraint explicit in backend technical documentation.

### Single-call determination

Before invoking the model, the backend gathers enough relevant authoritative information for one multimodal request to resolve the interaction:

```text
original audio OR typed text
+
relevant conversation
+
User Context / preferences
+
relevant Daily Plan / Events
+
now / timezone
+
Butler instructions and supported mutations
        ↓
ONE multimodal provider API call
        ↓
user_message_text
proposed_updates
Butler response text
matching Butler response audio
```

`proposed_updates` may represent supported Daily Event add/update/remove/skip operations, User Context/preference changes, other supported mutations, or no state change for a query/conversation.

Application/domain code remains authoritative: AI output is validated before mutation and the model never writes directly to PostgreSQL.

### Removed normal second-call behavior

The normal backend workflow must not perform a second AI request after understanding/domain processing solely to synthesize response audio.

```text
OLD IMPLEMENTATION SHAPE
multimodal understanding call
        ↓
domain processing
        ↓
second AI response-audio call

V1.7 TARGET
complete context
        ↓
ONE multimodal call
        ↓
transcript + proposed changes + response text + matching audio
        ↓
validate/apply + persist + deliver
```

This intentionally relies more heavily on the multimodal model so normal interactions use fewer provider round trips, reducing latency and provider-call overhead/cost while keeping response text and spoken audio in the same model result.

Media normalization such as FFmpeg WAV finalization may still run after the call. It is deterministic media processing, not another AI generation request, and must not change spoken content.

### Unchanged behavior

- Order/Talk preserve original recorded audio through the AI reasoning boundary.
- Order/Talk remain recorded asynchronous interactions, not realtime streaming.
- Text remains direct typed/editable input with no STT.
- Audio and text share one Butler product/domain behavior and async lifecycle.
- AI-proposed state changes are validated by deterministic application/domain code.
- One canonical Butler message is used for in-app and notification presentation.
- FCM remains a completion/wake-up signal rather than canonical content or audio transport.
- Response audio is temporarily stored on the backend and cached locally by Android.

---

## Version 1.6 — 2026-09-13

### Scope

Version 1.6 corrected the backend AI-processing contract for Order/Talk by preserving original recorded audio through multimodal semantic reasoning instead of using standalone transcription as the sole reasoning input.

Updated to `1.6`:

- `PROJECT.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`

Client transport, Room, WorkManager, FCM, notification, and UI behavior from v1.5 remained unchanged.

Version 1.7 supersedes v1.6 where v1.6 allowed more than one internal provider round trip for a normal interaction.

---

## Version 1.5 — 2026-09-12

Version 1.5 refined the v1.4 async Butler architecture by separating input transport from Butler reasoning and changing Text to direct typed/editable input.

Updated to `1.5`:

- `PROJECT.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`
- `client/CLIENT_ARCHITECTURE.md`
- `client/CLIENT_WORKFLOW.md`
- `client/CLIENT_SYNC_FLOW.md`

`ENGINEERING.md` remained `1.4` because no engineering rule changed.

---

## Version 1.4 — 2026-09-12

Version 1.4 introduced the asynchronous recorded-audio Butler interaction model: Order/Talk record then upload audio, requests continue asynchronously, FCM wakes the client on completion, foreground/background paths converge through one repository, canonical results persist in Room, and Butler response audio is downloaded/cached locally.

It also established one canonical response across notification and in-app presentation, compact speaker/private-listen actions, Open in App, limited backend audio retention, instant startup, and automatic Speak Aloud with Stop for Morning Brief and Good Night Summary.

---

## Versioning Rule

When a coordinated architecture/product change modifies multiple source-of-truth documents, update the affected documents to the same release version and record the change here.

Documents whose rules did not change do not require a version bump solely to match unrelated architecture documents.
