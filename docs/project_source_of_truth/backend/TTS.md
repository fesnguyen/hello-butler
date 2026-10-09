# TTS — Backend Speech Generation

**Status:** Source of Truth · **Parent:** [PROJECT_BACKEND.md](PROJECT_BACKEND.md)

## Responsibility

The backend owns speech generation and availability; the Android client owns audio caching, playback, volume, and scheduling. Canonical text and validated actions must persist independently of TTS success. FCM signals completion/readiness, not audio transport.

## Shared generation

Butler responses, Morning Brief, Good Night Summary, and reminder speech use the backend's shared TTS mechanism. The stored profile preference selects Open Source/Kokoro or OpenAI; insufficient OpenAI TTS credits trigger a runtime Kokoro fallback without changing the stored preference. Provider choice and credits are backend policy, not client policy.

## Lifecycle and reliability

Speech readiness is asynchronous (`pending → processing → ready | unavailable`). Text completion must not wait for optional TTS. The backend avoids duplicate generation with persisted request/audio state; explicit retries after unavailable speech may regenerate. For reminders, the existing backend supports on-demand speech preparation, but client-side timing and caching policy is documented in [AUDIO_WORKFLOWS.md](../client/AUDIO_WORKFLOWS.md).

## Ownership and changes

Update this document for backend speech API, generation, provider, credit, and readiness changes. Update [PROJECT_BACKEND.md](PROJECT_BACKEND.md) for architectural changes. Do not describe proposed prefetch-at-sync behavior as already implemented until the client/backend change is delivered.
