# Audio Response Reliability Report

Date: 2026-09-17

## Scope

This change hardens the complete Butler response-audio path: OpenAI generation, backend result delivery, Android download/cache, and Android playback.

## Problems found

1. Response-audio transcript validation required exact normalized text equality. Harmless differences such as punctuation, number formatting, or minor spoken wording caused the generated audio to be discarded.
2. The backend contract returns `audio/wav`, but Android cached every response as `<request-id>.mp3`. The extension and content therefore disagreed.
3. Android used blocking `MediaPlayer.prepare()` without guarding setup exceptions. An unsupported or malformed cached file could escape the service coroutine and crash the app.
4. Existing incorrectly named cached files were reused indefinitely.

## Changes made

### Backend

- Replaced exact transcript equality with a normalized sequence/token similarity score.
- Transcript mismatch is now advisory: it emits an operational warning and valid audio is retained. It no longer fails the Butler request.
- Added a RIFF/WAVE signature check before a generated response is stored as WAV.
- Added `warnings` to the request-result API. If text processing completed but response audio is unavailable, the API returns: `Response audio is unavailable; the text response is still complete.`
- Kept the public response-audio contract canonical: `Content-Type: audio/wav`, `.wav` filename, and WAV bytes.

### Android client

- Added support for the result `warnings` field and logs server warnings.
- Chooses the cache extension from the declared/returned MIME type instead of always using `.mp3`.
- Validates common audio container signatures (`wav`, `mp3`, `m4a`, `ogg`) before promoting a download into the durable cache.
- Deletes old cache entries whose extension/signature does not match the current contract, then downloads a clean copy.
- Downloads off the main thread.
- Uses `MediaPlayer.prepareAsync()` and catches playback setup failures. Invalid audio now stops playback cleanly instead of crashing the process.

## Current contract

The backend currently generates and serves response audio as WAV:

| Layer | Value |
|---|---|
| AI output request | WAV |
| Stored backend file | `<request-id>.wav` |
| HTTP `Content-Type` | `audio/wav` |
| Download filename | `butler-<request-id>.wav` |
| Android cache file | `<request-id>.wav` |

The Android downloader remains MIME-aware so a deliberate future backend migration to MP3, M4A, or OGG does not recreate the filename/content mismatch.

## Failure behavior

- A transcript mismatch does not reject otherwise valid audio.
- Missing, empty, invalid, or failed audio generation does not fail the completed text response. The client receives a warning and no audio URL.
- Unsupported or corrupt downloaded audio is marked failed and is never passed to `MediaPlayer`.
- A playback preparation/decoder error is logged, audio focus is released, and the service stops without crashing the app.

## Verification

- The new backend unit tests pass for tolerant matching, non-fatal transcript mismatch, and invalid-WAV rejection.
- Ruff passes for backend application and test code.
- The complete Pyright check still reports pre-existing LangGraph typing/stub errors outside this change.
- Android debug compilation was attempted, but the isolated build environment could not download Gradle. The Kotlin changes were therefore source-reviewed here and must also pass the repository CI or a connected local Android build before merge.

## Why this design

WAV is retained because it is the format already requested from the AI provider and exposed by the backend. Changing the entire contract to another codec would add migration risk without addressing the actual defect. The critical correction is making the bytes, MIME type, filename, cache validation, and player behavior agree at every boundary.
