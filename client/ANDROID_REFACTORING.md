# Targeted Android audio refactoring

Baseline: `162445c537124dde928334e673bd6b48a81f9a3d` on `main`.
Branch: `refactor/android_refactoring`.

## Responsibility map

Paths are relative to `app/src/main/java/com/hellobutler/app/`.

| Component | Responsibility |
| --- | --- |
| `execution/ButlerAudioPlaybackService.kt` | Android lifecycle, command admission, manual/automatic priority, execution ownership, generation, jobs/watchers, sequence orchestration, foreground presentation. |
| `execution/audio/AudioExecutionPreparer.kt` | Event claims and execution snapshots; response identity reservation and existing audio acquisition. |
| `execution/audio/AudioResourcePlayer.kt` | Sole MediaPlayer, audio focus, route, gain, player callback identity, resource cleanup. Uses the service's scope. |
| `execution/audio/EventSpeechPlayback.kt` | Connects the existing source resolver and offline synthesizer to event state and the shared player. |
| `data/repository/DailyEventRepository.kt` | Public repository API, Room persistence, claims, network preparation, synchronization, locks, scheduling coordination. |
| `data/repository/DailyEventSpeechStore.kt` | Prepared files, protected snapshots, atomic publication, proven local-edit rebinding, cleanup, persisted voice invalidation. Owns no locks or Room access. |
| `execution/audio/AudioWorkflowScheduler.kt` | Durable work/alarms, cancellation, recovery, guarded offer transitions. |
| `execution/audio/AudioWorkflowNotifications.kt` | Continuation channels, PendingIntents, waiting/Listen presentation and dismissal. |

The former service `prepare()` is now `AudioExecutionPreparer.prepare()`;
`speakEvent()` is now `EventSpeechPlayback.speak()`. The shared
`matchesExecution()` extension lives in `execution/DailyEventExecution.kt`, with
the same package and predicate. Existing callers and persisted identities remain
compatible. `AudioSequence`, `AudioWorkflowStore`, workflow definitions, and the
continuation receiver/worker implementations are unchanged.

## Preserved concurrency boundaries

- The service retains `generation`. Release invalidates generation and clears
  execution ownership before cancelling the watcher, stopping synthesis,
  cancelling the job, releasing player/focus, and resetting published state.
- Job exception/finally handlers still require a matching generation. The
  player checks both player identity and continuation activity before and after
  suspending eligibility checks. Focus callbacks check focus-request identity.
- Event watchers still run in the service scope. Extracted components introduce
  no independent scope or background lifecycle.
- Automatic response identity is still reserved before downloading. Event
  claims remain independent of prepared speech availability.
- The repository keeps `syncMutex`, `downloadMutex`, and `audioStateMutex`.
  Existing nesting remains sync -> audio state or download -> audio state.
  Network requests do not acquire the audio-state lock; canonical validation
  and publication do. Per-result and per-snapshot Room transactions are unchanged.
- Notification publication still runs inside the store's guarded transition,
  before the offered state is saved. Work names, tags, KEEP policy, alarm timing,
  PendingIntent identities, notification IDs/channels, and recovery decisions
  are retained.

## Regression coverage

New instrumentation coverage checks stale prepared-player callbacks after
replacement; storage publication, protected-file retention, invalidation,
cleanup and rebinding; stale alarm rejection and audio-independent event claims;
failed outbox retention and multi-result canonical reconciliation; and stale
notification offers. Existing playback coverage additionally checks preview,
Stop Preview, duplicate requests, and automatic requests around manual speech.

Run from `client/`:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Verification on 2026-10-17:

| Check | Result |
| --- | --- |
| Debug app assembly | Passed |
| JVM unit tests | 21 passed, no failures or skipped tests |
| Instrumentation test APK assembly | Passed |
| Android debug lint | Passed; no errors |
| Standalone audio workflow contracts | Six groups passed |
| Device instrumentation and audible acceptance | Not run; `adb devices` reports no attached device |
| Target imports, shared predicate, continuation receiver/worker | Existing imports preserved; predicate relocated unchanged; receiver/worker unchanged |

Device execution is required to establish Android player/focus, notification,
offline-engine and lifecycle behavior; instrumentation compilation alone cannot
establish those results.

## Existing documentation discrepancy

The configurable-audio paragraph in `PROJECT_CLIENT.md` still describes a
missing-speech path without recovery/native fallback. The actual implementation,
`PROJECT.md`, `AUDIO_WORKFLOWS.md`, and `AUDIO_FLOW_TRACE.md` include bounded
recovery and offline native synthesis. This refactoring preserves the actual
implementation and does not change that product behavior or edit unrelated
documentation outside `client/`.
