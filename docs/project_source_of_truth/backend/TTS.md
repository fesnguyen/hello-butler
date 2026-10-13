# TTS — Backend Speech Generation

**Status:** Source of Truth · **Parent:** [PROJECT_BACKEND.md](PROJECT_BACKEND.md)

## Responsibility

The backend owns speech generation and availability; the Android client owns audio caching, playback, volume, and scheduling. Canonical text and validated actions must persist independently of TTS success. FCM signals completion/readiness, not audio transport.

## Shared generation

Butler responses, Morning Brief, Good Night Summary, reminders, and other eligible speech-enabled Daily Events use the backend's shared TTS mechanism. The stored profile preference selects Open Source/Kokoro or OpenAI; insufficient OpenAI TTS credits trigger a runtime Kokoro fallback without changing the stored preference. Provider choice and credits are backend policy, not client policy.

## Lifecycle and reliability

Speech readiness is asynchronous (`pending → processing → ready | unavailable`). Text completion must not wait for optional TTS. The backend avoids duplicate generation with persisted request/audio state; explicit retries after unavailable speech may regenerate. Canonical event mutations queue/invalidate speech through the existing `DailyPlanChanges` transaction hook. The generalized preparation endpoint supports background client preparation, and the client’s single bounded recovery at its speech step. Normal alarm scheduling remains independent of generation. Client timing and durable storage are documented in [AUDIO_WORKFLOWS.md](../client/AUDIO_WORKFLOWS.md).

## Ownership and changes

Update this document for backend speech API, generation, provider, credit, and readiness changes. Update [PROJECT_BACKEND.md](PROJECT_BACKEND.md) for architectural changes. Scheduled-event background preparation is implemented; event ID/version remains the sole identity. GET requires the requested version and returns an `X-Event-Version` header. Speech-relevant mutations invalidate ready metadata; timing/order-only versions can rebind validated bytes without new synthesis. Changed-version generation is discarded. No schema migration is required.

Deploy the backend and updated Android client together: scheduled-event audio GET now requires `version`. Older clients omit that parameter. Butler Response audio routes are unchanged. Manual briefs now use the shared configured event workflow, preferring prepared audio before bounded recovery and offline native fallback.

A profile TTS-method change invalidates eligible planned event speech and increments canonical event versions. Unchanged narration/timing-only versions can reuse validated backend bytes; the client only rebinds its own confirmed compatible edit, otherwise downloading version-validated audio. Literal own-time references are retimed when an ordinary event moves; a brief’s delivery-time edit does not rewrite its summary. Changing deployment-level provider voice/model configuration is not automatically fingerprinted: invalidate retained event audio when performing such a deployment change.

Morning Brief and Good Night generation begin with substantive content; prerecorded Android workflows supply greetings. Their actual prompts and draft validators enforce this separation.
