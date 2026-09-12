# Source of Truth Version Updates

This file records coordinated version updates for documents under `docs/project_source_of_truth/`.

---

## Version 1.4 — 2026-09-12

### Scope

This release aligns the core source-of-truth documents on the new asynchronous recorded-audio Butler interaction model.

Updated to version `1.4`:

- `PROJECT.md`
- `ENGINEERING.md`
- `backend/BACKEND_ARCHITECTURE.md`
- `backend/BACKEND_WORKFLOW.md`
- `client/CLIENT_ARCHITECTURE.md`
- `client/CLIENT_WORKFLOW.md`
- `client/CLIENT_SYNC_FLOW.md`

### Product changes

- Order and Talk use press-and-hold recorded audio, then upload on release.
- The normal Butler voice path is no longer local STT → text → backend → local TTS.
- Voice is not a persistent realtime/WebRTC conversation.
- Conversation history remains visible while recording.
- Recording uses only a small in-message animation; no dedicated recording screen is required.
- After release, the client creates a local `Sending...` placeholder.
- After backend acceptance/handling acknowledgement, the placeholder becomes `Sent • <time>`.
- After completion, the temporary user placeholder is replaced by the backend-produced transcript.
- A completed Order/Talk request creates one user conversation message and one Butler conversation message.
- Notification and in-app presentation are two surfaces for the same Butler response, not separate responses.
- Text mode sends audio to the backend and receives context-aware editable text instead of relying on local STT as the authoritative path.

### Backend changes

- Introduced the asynchronous Butler request mental model using a stable `request_id`.
- Request creation returns quickly (conceptually `202 Accepted`) while processing continues asynchronously.
- Backend processing includes speech understanding/transcription, Butler reasoning, tools/domain changes, response text generation, and response audio generation.
- FCM carries lightweight handling/completion signals, not response audio.
- Completed results are fetched through authenticated HTTP.
- Backend conversation text remains durable while audio uses limited retention.
- User input audio is treated as temporary processing data unless a future requirement says otherwise.

### Client changes

- Foreground completion uses coroutine/repository work.
- Background completion uses WorkManager.
- Both paths converge through Retrofit/OkHttp, Room, and the same repository/persistence logic.
- Client begins downloading/caching Butler response audio immediately after completion result retrieval.
- Audio playback actions remain visible even while audio is still downloading; selecting them waits for the active download when necessary.
- Butler response audio is stored locally for historical playback.
- Old server audio may be unavailable after reinstall because backend audio is retention-limited.
- App startup prioritizes rendering and immediate voice capture; plan/history synchronization runs in the background.

### Notification changes

Ordinary Butler message notifications expose:

- speaker icon for Speak Aloud
- phone/private-listen icon for receive-as-call style playback
- `Open in App`

`Open in App` remains present for Butler notifications.

### Morning Brief / Good Night Summary changes

- Morning Brief automatically starts Speak Aloud when delivered/due.
- Good Night Summary automatically starts Speak Aloud when delivered/due.
- Both must expose an immediate Stop action while playing.
- Both retain `Open in App`.
- Ordinary Butler responses remain silent by default.

### Compatibility / implementation note

Version 1.4 defines the intended architecture and behavior. Existing implementation may still reflect older local-STT/local-TTS or synchronous request paths until the corresponding implementation task is completed. Code changes should follow the v1.4 documents rather than preserving obsolete behavior solely for compatibility.

---

## Versioning Rule

When a coordinated architecture/product change modifies multiple source-of-truth documents, update them to the same release version and record the change here.

Minor wording changes that do not alter behavior or architecture do not require a coordinated version bump.
