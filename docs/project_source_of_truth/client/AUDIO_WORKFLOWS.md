# Configurable Butler Audio

**Status:** Source of Truth
**Version:** 1.0
**Authority:** Derived from PROJECT.md, ENGINEERING.md, and CLIENT_ARCHITECTURE.md

## Configuration

Edit `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioWorkflow.kt`:

```ini
MORNING_BRIEF_AUDIO="PLAY(long_opening);PLAY(morning_warmup);DELAY(300);PLAY(short_opening);PLAY(<speech>)"
GOOD_NIGHT_AUDIO="PLAY(long_opening);PLAY(evening_warmup);PLAY(<speech>)"
REMINDER_AUDIO="PLAY(short_opening);PLAY(<speech>)"
BUTLER_RESPONSE_AUDIO="PLAY(<speech>)"
```

Definitions are Android build-time configuration, not remotely fetched executable code. Every workflow requires exactly one `PLAY(<speech>)`. Semicolons separate commands, surrounding whitespace is allowed, and names are lowercase identifiers. No expressions, arbitrary code, negative delays, empty commands, or trailing separators are accepted. Definitions are limited to 4096 characters/64 operations; delay accepts integer seconds from 0 through 86400. Zero delay proceeds immediately; other delays use persistent scheduling.

| Command | Meaning |
| --- | --- |
| `PLAY(long_opening)` / `PLAY(short_opening)` | Bundled opening sounds |
| `PLAY(morning_warmup)` | Independent shuffle over morning_warmup_0…4 |
| `PLAY(evening_warmup)` | Independent shuffle over evening_warmup_0…4 |
| `PLAY(<speech>)` | Caller-supplied existing backend TTS audio |
| `DELAY(300)` | Continue no earlier than 300 seconds after previous playback completion |

`AudioResources` maps names explicitly to `R.raw` IDs; add new bundled resources there and groups in `AudioWorkflows.groups`. Failed/missing optional audio is skipped. Missing/failed dynamic speech ends the execution gracefully. Original MP3 assets are unchanged. `volume_preview.mp3` is a byte-for-byte copy of `morning_warmup_0.mp3`.

## Playback and persistence

`AudioSequence` drives operations using injected playback, journal, scheduler, and clock functions. Playback completes before the next operation is checkpointed. Group selection persists the remaining queue before playback; no group repeats a recording until all five have been selected. The groups do not consume one another's queues.

The execution journal stores ID, workflow definition snapshot, speech path, event ID/version or request ID, automatic/manual mode, route, next operation, due time, status, and creation/update timestamps. Scheduled event IDs use event/version identity; automatic response IDs use request identity. Manual responses create separate runs and suppress pending automatic playback of that response.

Transitions: ready → playing → ready; delay → waiting; blocked background launch → offered; final completion → complete; Stop/interruption/failure → cancelled. Journal writes are synchronous, and stale notification work cannot overwrite a newer checkpoint. Automatic requests defer around another request; explicit manual playback cancels the active one. Preview is ignored while another request owns playback.

Delayed continuation uses uniquely named WorkManager work keyed by execution/next operation/due time, tagged by execution for cancellation. Exact alarms supplement it when permission exists, retaining the existing scheduled foreground-service exemption. The service, player, and focus are released at delay time. Waiting notifications expose Stop. Restart repairs a persisted journal/enqueue gap; logout cancels execution work/alarms and clears workflow speech files.

The journal deliberately provides at-most-once automatic audible segments, not an impossible atomic commit with a physical speaker. If the process dies during a playing segment, restart cancels that ambiguous run rather than repeats its opening/speech. Waiting runs survive process death. Existing Room event claims still prevent duplicate initial event execution. Force-stopping Android apps pauses background execution until the app can run again.

Event/version/status/date are rechecked before continuing. Prepared event audio is copied into internal files storage for a delayed run, independent of evictable cache files. Automatic conversation playback is limited to recent requests/results (ten minutes); historical reconciliation does not announce old conversations. Terminal journals are pruned after seven days.

## Sound & Voice

| Setting | Default | Scope |
| --- | --- | --- |
| Butler volume | 70% | 0–100% player gain, including openings, warm-ups, preview, and speech |
| Speak reminders aloud | Off | Automatic reminders |
| Speak Butler responses aloud | Off | Eligible recent conversation completions |
| Speak daily briefings aloud | On | Morning Brief and Good Night |

Settings are local to the device and persist across app restarts. Volume updates immediately through the player; no Android stream-volume setting is changed. Device volume still limits audible output. The slider starts one prerecorded sample per adjustment and stops it when adjustment ends; missing preview is silently skipped and never synthesized.

Automatic preferences are checked by alarm delivery, workers, and service, including after a delay. Explicit manual Listen bypasses automatic-speech preferences. Ordinary notifications and canonical text remain available. FCM can start automatic response playback only while foreground or when a delivered high-priority push provides the Android exemption. Other background completions offer Listen. Do not force a forbidden background start.

## Reminder TTS

The existing shared backend SpeechService supports reminder events, with content → description → title fallback. Android calls authenticated `POST /api/sync/events/{id}/speech?version=N` once when spoken reminder playback is requested, then polls/downloads the existing audio endpoint. Ownership, planned status, and version are validated. Pending/processing/ready owners reuse their existing lifecycle; the TTS row claim prevents duplicate generation. Reminder speech is not generated merely to show a normal notification or when automatic reminder speech is disabled. An explicit retry after unavailable speech can request generation again. Provider settings and credits/fallback policy are unchanged.

## Verification

From `client/`:

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
```

Unit checks cover strict parsing, feature sequence order, independent rotation, gain/preferences, completion-relative persistent delays, and cancellation/failures. Instrumented checks cover raw resource resolution, restart persistence, stale-work suppression, and Android volume isolation; existing ButlerPlaybackTest covers route switching/Stop. Device checks must verify the locked-device alarm, five-minute restart continuation, Android foreground-service restrictions, interruptions, preview, and all automatic preference combinations.

On-device WorkManager timing is a minimum delay, not an exact delivery guarantee. Exact alarms remain permission-dependent. Background/device behavior and the full Android build must be validated in an environment with the required Gradle plugin/dependencies and a device/emulator.
