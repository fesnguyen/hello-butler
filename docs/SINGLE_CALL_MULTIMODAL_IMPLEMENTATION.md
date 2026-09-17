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
  -> load conversation, User Context, plan/events, time and timezone
  -> OpenAIButlerProvider.interact()                         [one API call]
       -> ButlerInteractionProposal
            user_message_text
            ButlerDecision
            response_text
       -> native assistant response audio
  -> validate/apply the proposed action through application services
  -> persist canonical response_text and changed entities
  -> store normalized WAV returned by the same call
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

## Canonical response behavior

`response_text` from the single model response remains unchanged through action
execution, history persistence, request persistence, notification, and Android
presentation. The audio is generated in that same response and is checked against
the canonical response transcript before local WAV normalization.

## Validation and failures

- The model only proposes changes. Existing action services resolve ownership,
  targets, required fields, dates, and database mutations.
- An invalid/unresolvable mutation raises `ButlerMutationRejectedError`. The
  request becomes failed and no false success message is written to conversation
  history.
- Provider or structured-schema failure fails the request and remains observable.
- Missing, malformed, or unnormalizable response audio is logged and degrades to
  the existing completed text-only response with an audio warning. It never causes
  a second provider call.
- A materially different audio transcript is rejected into the same text-only
  fallback; tolerant normalization still permits harmless punctuation/formatting
  variation.
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
  unchanged canonical response persistence, WAV storage, malformed-audio fallback,
  mutation rejection, and existing recovery/idempotency behavior.
- Pyright was run and continues to report the repository's existing LangGraph and
  third-party typing/stub issues; no new single-call contract error was reported.
