# Single-Call Multimodal Butler Implementation

Date: 2026-09-17  
Source-of-truth version: 1.7

## Result

Normal Butler audio and text interactions now make one multimodal OpenAI request.
That response contains the typed application proposal and native response audio.
There is no later OpenAI/TTS request.

## Flow

```text
audio or text
  -> Android records voice as Ogg/Opus and streams/uploads it to the backend
  -> backend stores the Opus input only as a temporary durable request asset
  -> backend converts Opus -> WAV before the OpenAI multimodal call
  -> load conversation, User Context, plan/events, time and timezone
  -> OpenAIButlerProvider.interact()                         [one API call]
       -> ButlerInteractionProposal
            user_message_text
            ButlerDecision
            response_text
       -> native assistant response audio
  -> validate/apply the proposed action through application services
  -> persist canonical response_text and changed entities
  -> normalize model WAV output, then encode WAV -> Ogg/Opus
  -> store the Opus response for later client download
  -> delete the temporary input voice after successful processing
  -> complete the durable request and notify the client
```

## Contract

- `ButlerInteractionProposal` is the Pydantic tool schema. It reuses the existing
  `ButlerDecision` action contract rather than introducing dictionary mutations.
- `ButlerAIInteraction` keeps the proposal and decoded audio separate at the
  provider boundary.
- `ButlerCompletedInteraction` carries the validated domain result and normalized
  audio from the graph to durable request processing.
- Supported mutations now include event create/update/skip/remove and User Context
  create/update. Queries and clarifications use no mutation.
- Context loading supplies conversation from the configured 30-minute window and
  relevant events from today through the next seven days before the single call.

## Audio transport and storage contract

The v1.7 audio contract is explicit:

- **Client recording/upload:** Ogg container with Opus audio (`audio/ogg`). The client streams/uploads this compressed format to the backend; it does not upload WAV.
- **Android compatibility:** framework `MediaRecorder` exposes the required OGG + OPUS pair from Android 10 (API 29). Voice capture fails clearly on API 26-28 instead of silently producing AAC under an Ogg name; supported production voice devices must run API 29 or newer.
- **Temporary backend input:** the uploaded Ogg/Opus asset is stored only long enough to support durable asynchronous processing/recovery. Before the OpenAI call, the backend converts it to WAV.
- **OpenAI input/output:** WAV is the normalized internal provider format. WAV is not the client transport/storage format.
- **Backend response conversion:** audio returned by OpenAI is normalized as WAV first, then encoded to Ogg/Opus before durable response storage.
- **Client response:** the client downloads and plays the stored Ogg/Opus response (`audio/ogg`). Response metadata, extension, validation, caching, and playback must agree with that format.
- **Input lifetime:** after successful request processing, the temporary input voice asset is deleted automatically and its persisted path is cleared. Failed/recoverable work may retain it only as required by retry/recovery, after which cleanup removes it.
- **Output lifetime:** response Opus is retained for a configurable number of days so the user can download/play it later. The application default is **1 day**. This is a code/configuration default and does **not** require an explicit `.env` entry; deployments may override it.
- FFmpeg is the backend codec boundary for Opus <-> WAV conversion and is therefore a required runtime dependency for voice processing.

This keeps network and durable response audio compressed while giving the model one stable WAV boundary:

```text
Android Ogg/Opus
  -> backend temporary Ogg/Opus
  -> FFmpeg -> WAV
  -> OpenAI single multimodal call
  -> WAV
  -> FFmpeg -> Ogg/Opus
  -> backend retained response (default 1 day)
  -> Android download/playback
```

## Canonical response behavior

`response_text` from the single model response remains unchanged through action
execution, history persistence, request persistence, notification, and Android
presentation. The audio is generated in that same response and is checked against
the canonical response transcript before backend normalization and Opus encoding.

## Validation and failures

- The model only proposes changes. Existing action services resolve ownership,
  targets, required fields, dates, and database mutations.
- An invalid/unresolvable mutation raises `ButlerMutationRejectedError`. The
  request becomes failed and no false success message is written to conversation
  history.
- Provider or structured-schema failure fails the request and remains observable.
- **Input-audio failures are request failures:** invalid Ogg/Opus, unavailable FFmpeg,
  or an undecodable upload means Butler has no trustworthy user instruction. The
  durable input is retained temporarily for retry/recovery and later maintenance cleanup.
- Missing, malformed, unnormalizable, or unencodable response audio is logged and degrades to
  the existing completed text-only response with an audio warning. It never causes
  a second provider call.
- **Response-audio failures never fail the request:** absent model audio, decode
  failure, materially unrelated speech, invalid WAV, Opus encoding failure, or
  storage failure preserves the canonical text/action, completes the request,
  returns `response_audio_url = null`, and emits a warning plus a cause-specific log.
- The transcript check is deliberately permissive and acts only as a strong-mismatch
  sanity guard. Valid decoded and normalized audio is preserved by default, including
  short semantic paraphrases with little or no lexical overlap. Audio is dropped only
  when the transcript provides strong evidence of being materially unrelated.
  `proposal.response_text` remains canonical regardless of the decision.
- Idempotency receipts and asynchronous recovery remain active for retried durable
  requests.

## Removed architecture

- `ButlerResponseAudioProvider` and `synthesize()` were removed.
- `BUTLER_DECISION_INSTRUCTIONS` plus `BUTLER_RESPONSE_AUDIO_INSTRUCTIONS` were
  replaced for interaction processing by `BUTLER_INTERACTION_INSTRUCTIONS`.
- Backend-generated query summaries and canned successful mutation responses no
  longer replace the model's canonical response.

## Verification

- Backend unit suite: 22 tests passed.
- Ruff: passed for application and tests.
- Tests cover the one-call invariant, original audio/context input, text input,
  event create/update/skip, User Context update, query/clarification no-op,
  unchanged canonical response persistence, response-audio storage, malformed-audio fallback,
  mutation rejection, and existing recovery/idempotency behavior.
- Pyright was run and continues to report the repository's existing LangGraph and
  third-party typing/stub issues; no new single-call contract error was reported.
