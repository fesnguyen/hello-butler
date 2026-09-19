# Two-Call Butler Interaction and Voice Implementation

Date: 2026-09-19  
Source-of-truth version: 1.7

## Decision

Hello Butler no longer treats a single multimodal OpenAI call as the target architecture.

The interaction is split into two responsibilities:

1. **Understanding and response generation:** accept user text or audio, understand the request with Butler context, and return the structured `ButlerInteractionProposal` including the canonical `response_text`.
2. **Optional text-to-speech:** when Butler voice is enabled for the user, synthesize that already-final `response_text` into response audio.

Text is the canonical Butler response. Voice is an optional presentation feature layered on top of the completed text response.

## Why the single-call design was removed

The single-call experiment tried to obtain both a machine-facing structured interaction and independent native speech in one assistant response.

Two behaviors were observed:

- A forced function/tool call reliably returned the structured Butler proposal, but the assistant turn did not return native response audio.
- A normal audio assistant response returned native audio, but when the assistant response was constrained to the proposal JSON, the generated audio transcript represented that JSON rather than speaking only the nested `response_text`.

The audio response and its transcript therefore cannot be treated as independent channels where the transcript carries the complete structured proposal while the audio speaks only one selected field.

The architecture now keeps structured understanding and speech synthesis separate instead of relying on prompt instructions or transcript-similarity heuristics to bridge those responsibilities.

## Flow

### Text request without Butler voice

```text
user text
  -> load conversation, User Context, plan/events, time and timezone
  -> OpenAI interaction/understanding call
       -> ButlerInteractionProposal
            user_message_text
            ButlerDecision
            response_text
  -> validate/apply the proposed action through application services
  -> persist canonical response_text and changed entities
  -> complete the durable request and notify the client
  -> Android displays response_text
```

This path requires no response-audio generation.

### Audio request without Butler voice

```text
Android records Ogg/Opus
  -> backend stores temporary Ogg/Opus
  -> FFmpeg -> WAV
  -> OpenAI interaction/understanding call
       audio + Butler context
       -> ButlerInteractionProposal
            user_message_text
            ButlerDecision
            response_text
  -> validate/apply the proposed action
  -> persist canonical response_text and changed entities
  -> delete temporary input audio after successful processing
  -> complete request
  -> Android displays response_text
```

Input audio and Butler response voice are separate capabilities. A user may speak a request without requiring a synthesized Butler voice response.

### Request with Butler voice enabled

```text
CALL 1 - UNDERSTAND AND RESPOND

user text or normalized user audio
  -> OpenAI interaction/understanding
  -> ButlerInteractionProposal
       user_message_text
       ButlerDecision
       response_text
  -> validate/apply proposed action
  -> response_text becomes canonical

CALL 2 - OPTIONAL SPEECH

canonical response_text
  -> text-to-speech
  -> WAV
  -> FFmpeg -> Ogg/Opus
  -> backend retained response audio
  -> Android download/playback
```

The second call receives the exact canonical `response_text`. It does not independently decide what Butler should say.

## Contract

- `ButlerInteractionProposal` remains the structured interaction contract.
- `ButlerDecision` remains the machine-facing action/query decision.
- `response_text` is the single canonical user-facing Butler response.
- The interaction model may consume text or audio, but its responsibility ends with structured understanding and canonical text generation.
- Text-to-speech receives only the finalized `response_text`.
- Response audio is optional. A completed Butler request does not require response audio.
- Voice availability is an application capability/entitlement concern and must not change the meaning or execution of the Butler interaction.
- Supported mutations remain event create/update/skip/remove and User Context create/update. Queries and clarifications use no mutation.
- Context loading continues to use the configured conversation window plus relevant events and User Context.

## Audio transport and storage contract

The client/backend transport contract remains compressed Ogg/Opus:

- **Client recording/upload:** Android records Ogg/Opus (`audio/ogg`).
- **Android compatibility:** framework OGG + OPUS recording requires Android 10 (API 29) or newer.
- **Temporary backend input:** uploaded Ogg/Opus is retained only as required for durable processing/recovery.
- **Understanding input:** before an audio interaction call, backend FFmpeg converts Ogg/Opus to the provider-supported normalized audio format.
- **Speech output:** when voice is enabled, text-to-speech receives the canonical `response_text` and returns response audio.
- **Backend response conversion:** provider response audio is normalized as needed and encoded to Ogg/Opus before durable storage.
- **Client response:** Android downloads and plays Ogg/Opus (`audio/ogg`).
- **Input lifetime:** successful processing deletes temporary input audio and clears its persisted path. Failed/recoverable work may retain it temporarily for retry/recovery.
- **Output lifetime:** synthesized response audio remains subject to the configured retention period; the current application default is 1 day.
- FFmpeg remains the backend codec boundary for client audio transport and provider audio formats.

```text
INPUT AUDIO

Android Ogg/Opus
  -> backend temporary Ogg/Opus
  -> FFmpeg -> provider input format
  -> interaction/understanding call
  -> structured proposal + canonical response_text


OPTIONAL BUTLER VOICE

canonical response_text
  -> text-to-speech call
  -> provider audio
  -> FFmpeg -> Ogg/Opus
  -> backend retained response
  -> Android playback
```

## Canonical response behavior

`response_text` is finalized by the interaction call before text-to-speech begins.

The same value is used for:

- conversation history,
- durable request result,
- notifications/client presentation,
- text-to-speech input when Butler voice is enabled.

The speech call must not paraphrase, rewrite, summarize, or make new decisions. Its job is presentation only.

This removes the previous need to compare independently generated speech against canonical text as an acceptance gate. The TTS input itself is the canonical response.

## Voice is optional

Butler must remain fully functional without synthesized response audio.

When voice is unavailable or disabled:

```text
interaction succeeds
  -> action/query result is applied
  -> response_text is persisted
  -> response_audio_url = null
  -> client presents text
```

When voice is enabled:

```text
interaction succeeds
  -> response_text is persisted
  -> TTS is attempted
  -> successful audio is stored and exposed to the client
```

A TTS failure must not undo an already successful Butler interaction. The request remains completed with its canonical text and action result, while response audio is omitted and the failure is logged.

User entitlement, billing, and default voice-access policy are intentionally outside this v1.7 implementation document.

## Validation and failures

- The interaction model only proposes changes. Existing application services validate ownership, targets, required fields, dates, and database mutations.
- An invalid or unresolvable mutation fails the interaction and must not write a false success message.
- Structured interaction/schema failure fails the request.
- Input-audio failures can fail an audio request because Butler cannot reliably understand the user's instruction.
- TTS and response-audio processing failures are non-fatal because the canonical interaction has already completed.
- No TTS retry may regenerate or alter the canonical `response_text`.
- Durable request recovery and idempotency remain responsible for preventing duplicate interaction mutations.

## Implementation direction

The existing v1.7 single-call code is transitional and must be refactored to match this document:

- remove native response-audio generation from the interaction/understanding provider call;
- restore a dedicated response speech/TTS boundary;
- pass only finalized `response_text` into that boundary;
- invoke TTS only when Butler voice is enabled for the user;
- keep text-only completion as the normal valid result when voice is disabled or unavailable;
- retain Ogg/Opus client transport and response storage;
- update tests from a one-call invariant to separate interaction and optional-TTS invariants.

This document records the architecture decision only; it does not claim the current branch implementation already satisfies the two-call design.
