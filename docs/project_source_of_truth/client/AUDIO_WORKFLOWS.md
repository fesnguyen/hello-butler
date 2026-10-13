# Configurable Butler Audio

**Status:** Source of Truth
**Version:** 2.0
**Authority:** Derived from PROJECT.md, ENGINEERING.md, and PROJECT_CLIENT.md

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
| `PLAY(<speech>)` | Resolved Daily Event speech, or existing backend response audio |
| `DELAY(300)` | Continue no earlier than 300 seconds after previous playback completion |

`AudioResources` maps names explicitly to `R.raw` IDs; add new bundled resources there and groups in `AudioWorkflows.groups`. Failed/missing optional audio is skipped. Missing/failed dynamic speech ends the execution gracefully. Original MP3 assets are unchanged. `volume_preview.mp3` is a byte-for-byte copy of `morning_warmup_0.mp3`.

## Playback and persistence

`AudioSequence` drives operations using injected playback, journal, scheduler, and clock functions. Playback completes before the next operation is checkpointed. Group selection persists the remaining queue before playback; no group repeats a recording until all five have been selected. The groups do not consume one another's queues.

The execution journal stores ID, workflow definition snapshot, speech path, event ID/version or request ID, automatic/manual mode, route, next operation, due time, status, and creation/update timestamps. Scheduled event IDs use event/version identity; automatic response IDs use request identity. Manual responses create separate runs and suppress pending automatic playback of that response.

Transitions: ready → playing → ready; delay → waiting; blocked background launch → offered; final completion → complete; Stop/interruption/failure → cancelled. Journal writes are synchronous, and stale notification work cannot overwrite a newer checkpoint. Automatic requests defer around another request; explicit manual playback cancels the active one. Preview is ignored while another request owns playback.

Delayed continuation uses uniquely named WorkManager work keyed by execution/next operation/due time, tagged by execution for cancellation. Exact alarms supplement it when permission exists, retaining the existing scheduled foreground-service exemption. The service, player, and focus are released at delay time. Waiting notifications expose Stop. Restart repairs a persisted journal/enqueue gap; logout cancels execution work/alarms and clears workflow speech files.

The journal deliberately provides at-most-once automatic audible segments, not an impossible atomic commit with a physical speaker. If the process dies during a playing segment, restart cancels that ambiguous run rather than repeats its opening/speech. Waiting runs survive process death. Existing Room event claims still prevent duplicate initial event execution. Force-stopping Android apps pauses background execution until the app can run again.

Execution text/type/trigger/eligibility snapshots and canonical event/version/status are checked before continuation and player start. Automatic events must remain planned for today; explicit manual runs may use historical/future events but never canceled/skipped ones. Room observation cancels incompatible active edits. Prepared audio is copied into protected internal storage at the speech step; pruning never deletes a path referenced by an active journal. Automatic conversation playback is limited to recent requests/results (ten minutes); historical reconciliation does not announce old conversations. Terminal journals are pruned after seven days.

## Sound & Voice

| Setting | Default | Scope |
| --- | --- | --- |
| Butler volume | 70% | 0–100% player gain, including openings, warm-ups, preview, and speech |
| Speak reminders aloud | Off | Automatic reminders |
| Speak Butler responses aloud | Off | Eligible recent conversation completions |
| Speak daily briefings aloud | On | Morning Brief and Good Night |

Settings are local to the device and persist across app restarts. Volume updates immediately through the player; no Android stream-volume setting is changed. Device volume still limits audible output. The slider starts one prerecorded sample per adjustment and stops it when adjustment ends; missing preview is silently skipped and never synthesized.

Automatic preferences are checked by alarm delivery, workers, and service, including after a delay. Explicit manual Listen bypasses automatic-speech preferences. Ordinary notifications and canonical text remain available. FCM can start automatic response playback only while foreground or when a delivered high-priority push provides the Android exemption. Other background completions offer Listen. Do not force a forbidden background start.

## Scheduled event preparation

All eligible Morning Brief, Good Night Summary, Reminder and ordinary speech-enabled events follow canonical persistence → independent alarm scheduling + WorkManager preparation → durable local file → offline playback. Local unsynchronized edits schedule immediately but cannot download until backend confirmation. Reminders created from orders prepare at once after canonical sync; they never depend on nightly planning.

`DailyEventAudioWorker` uses CONNECTED constraints, unique event ID/version work with KEEP, and exponential retry from ten seconds. It does not play audio or poll in a live loop. Pending TTS, network/temporary errors and unavailable audio retry; stale versions request sync; deleted/unsupported/authentication-rejected requests terminate. Restart/restoration reconciles Room to recover a missed enqueue. Preparation is bounded by planned eligibility and the event’s device-local date. A playback attempt does not stop preparation during a warm-up/delay; workers never play late audio.

`DailyEventRepository.speechAudio()` calls the generalized preparation POST and versioned audio GET, validates `X-Event-Version` and Ogg/Opus, rechecks canonical Room state, and atomically publishes `filesDir/event_speech/{id}-{version}.ogg`. Obsolete versions/deletions/logout are cleaned. `claimEventPlayback()` claims an automatic workflow independently of readiness; `snapshotPreparedSpeech()` copies into existing `workflow_audio` at PLAY(<speech>); prepared-file cleanup does not remove active/delayed snapshots. No new Room table or identity system is used.

At alarm time, eligible events start the existing workflow and post their normal text notification. At PLAY(<speech>), SpeechSourceResolver prefers validated local audio, otherwise attempts recovery once when online for at most five seconds, then offline Android TTS. Recovery joins existing preparation when feasible and installs an absolute streaming deadline; no polling loop or late automatic playback is added. LocalTextToSpeech renders temporary WAV chunks with installed offline voice data, bounded to fifteen seconds per chunk, and the existing MediaPlayer supplies Butler gain, routing, focus and cancellation. Failed/unavailable speech restores the text notification and cancels the journal, not successful completion. An interrupted audible segment is never repeated with another source. Manual brief Listen follows the same workflow. Existing definitions/assets remain unchanged, including the long Good Night opening. System notification permissions/channel settings/DND still apply.

Backend canonical changes invalidate changed narration/type/eligibility through `DailyPlanChanges`. Reminders use content → description → title fallback, and other speech-enabled events use the same nonblank fallback. Timing/order-only changes reuse validated narration under the current event/version path. Audio GET distinguishes 202 pending, 409 stale, 404 missing, 410 deleted, 422 ineligible, 424 unavailable and temporary server failures. See [AUDIO_FLOW_TRACE.md](AUDIO_FLOW_TRACE.md) for concrete methods and acceptance tests.

## Verification

From `client/`:

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
```

Unit checks cover strict parsing, feature sequence order, independent rotation, gain/preferences, completion-relative persistent delays, and cancellation/failures. Instrumented checks cover raw resource resolution, restart persistence, stale-work suppression, and Android volume isolation; existing ButlerPlaybackTest covers route switching/Stop. Device checks must verify the locked-device alarm, five-minute restart continuation, Android foreground-service restrictions, interruptions, preview, and all automatic preference combinations.

On-device WorkManager timing is a minimum delay, not an exact delivery guarantee. Exact alarms remain permission-dependent. Background/device behavior and the full Android build must be validated in an environment with the required Gradle plugin/dependencies and a device/emulator.

Exact canonical confirmation of local timing edits can reuse compatible bytes under the new event/version; unproven remote version changes download validated audio, while backend reuse avoids regeneration. Literal own-time reference changes invalidate narration. TTS-method changes cancel event runs and invalidate prepared files/old snapshot eligibility until canonical synchronization. Device acceptance must include offline installed-voice synthesis, missing-voice failure, live Butler gain, focus loss, Stop, rescheduling during delay and process death. Instrumentation compilation does not verify audible output.
