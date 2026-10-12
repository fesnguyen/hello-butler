# TTS — Backend Speech Generation

**Status:** Source of Truth · **Parent:** [PROJECT_BACKEND.md](PROJECT_BACKEND.md)

## Responsibility

The backend owns speech generation and availability; the Android client owns audio caching, playback, volume, and scheduling. Canonical text and validated actions must persist independently of TTS success. FCM signals completion/readiness, not audio transport.

## Shared generation

Butler responses, Morning Brief, Good Night Summary, reminders, and other eligible speech-enabled Daily Events use the backend's shared TTS mechanism. The stored profile preference selects Open Source/Kokoro or OpenAI; insufficient OpenAI TTS credits trigger a runtime Kokoro fallback without changing the stored preference. Provider choice and credits are backend policy, not client policy.

## Lifecycle and reliability

Speech readiness is asynchronous (`pending → processing → ready | unavailable`). Text completion must not wait for optional TTS. The backend avoids duplicate generation with persisted request/audio state; explicit retries after unavailable speech may regenerate. Canonical event mutations queue/invalidate speech through the existing `DailyPlanChanges` transaction hook. The generalized preparation endpoint supports background client preparation, never scheduled playback-time synthesis. Client timing and durable storage are documented in [AUDIO_WORKFLOWS.md](../client/AUDIO_WORKFLOWS.md).

## Ownership and changes

Update this document for backend speech API, generation, provider, credit, and readiness changes. Update [PROJECT_BACKEND.md](PROJECT_BACKEND.md) for architectural changes. Scheduled-event background preparation is implemented; event ID/version remains the sole identity. GET requires the requested version and returns an `X-Event-Version` header. Speech-relevant mutations invalidate ready metadata; timing/order-only versions can rebind validated bytes without new synthesis. Changed-version generation is discarded. No schema migration is required.

Deploy the backend and updated Android client together: scheduled-event audio GET now requires `version`. Older clients omit that parameter. Butler Response audio routes and manual native briefing TTS are unchanged.
