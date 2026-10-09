# Configurable Audio Experience — implementation handoff

Branch: `feature/enhance_butler_voice`. Latest branch state was cloned and pulled before implementation. Implementation is committed on this existing branch for review; no new branch or PR was created.

## Decisions and behavior

- Central strict parser/configuration with one dynamic speech operation per workflow; optional raw resources and independent persisted five-recording warm-up groups.
- Extend ButlerAudioPlaybackService as the only player; scheduled speech's old service is an intent compatibility entry point. Shared audio focus, interruption/cancellation, route switching, completion handling, and per-player volume.
- Device-local Sound & Voice volume defaults to 70%, with immediate gain changes and one preview per slider adjustment. No global Android volume changes. Reminder/response speech default off and daily briefings default on.
- Synchronous execution journal checkpoints plus WorkManager continuations; permitted exact alarms supplement background starts. No player, focus, or live delay coroutine remains during five minutes. Waiting notifications expose Stop.
- Existing Room event/version claims and request journal identities prevent repeats. Manual response playback suppresses its pending automatic announcement. Automatic response eligibility is limited to ten minutes.
- Reminder speech is requested on demand through the existing backend shared TTS service and event audio fields; ordinary reminder notifications work independently. No voice/provider changes or new tables.
- Original MP3s unchanged. volume_preview.mp3 was copied from morning_warmup_0.mp3; SHA-256 matches exactly.

## Final definitions

```ini
MORNING_BRIEF_AUDIO="PLAY(long_opening);PLAY(morning_warmup);DELAY(300);PLAY(short_opening);PLAY(<speech>)"
GOOD_NIGHT_AUDIO="PLAY(long_opening);PLAY(evening_warmup);PLAY(<speech>)"
REMINDER_AUDIO="PLAY(short_opening);PLAY(<speech>)"
BUTLER_RESPONSE_AUDIO="PLAY(<speech>)"
```

## Verification

- Pure Kotlin executable contract checks: six groups passed, including invalid syntax, sequence order, rotation, volume/preferences, completion-relative delay/resumption, and failure/cancellation.
- Additional partial Kotlin type compilation of the actual audio engine/service against Android 35 APIs with stand-in app/AndroidX dependencies: passed. This is not an Android build, Compose/Room/serialization validation, or device test.
- Backend: `python -m unittest tests.test_credits_tts tests.test_response_audio`: 12 tests passed. New checks include reminder voice/title fallback, duplicate generation/credit charge prevention, mutation invalidation, on-demand preparation, pending-owner reuse, ownership and version validation.
- Modified backend application files: Ruff passed. Sync API imports/registers successfully with required runtime dependencies supplied.
- `git diff --check`: passed.
- `./gradlew testDebugUnitTest assembleDebug`: wrapper download blocked by unavailable Java network route. Retried with locally available Gradle/Android SDK, offline and through the supplied proxy: Android Gradle Plugin 8.9.1 could not be resolved. No APK or full Gradle unit test success is claimed.
- Instrumented persistence/resource/volume checks added, alongside existing route/Stop test. Not run: Android plugin resolution is blocked and `adb devices` reports no connected device.

## Remaining limits

All requested audio assets are present, including the copied preview. Android integration, Compose settings, Room queries, serialization-generated journal code, foreground/background service behavior, and APK installation require the full build/device checks in a configured environment.

WorkManager continuation timing is a minimum delay. Exact background autoplay depends on Android permissions/start exemptions; unavailable starts retain a Listen fallback. Force-stop pauses background execution until the app can run again. To prevent repeated announcements, process death during an audible segment cancels the ambiguous run; a persisted waiting delay resumes after restart. Physical audio output and storage cannot be atomically committed.

Configuration and operational details: [AUDIO_WORKFLOWS.md](../project_source_of_truth/client/AUDIO_WORKFLOWS.md).

## Changed files

- `backend/app/api/sync.py`
- `backend/app/application/butler/actions/daily_event.py`
- `backend/app/application/speech.py`
- `backend/app/application/sync/service.py`
- `backend/tests/test_credits_tts.py`
- `client/app/build.gradle.kts`
- `client/app/src/androidTest/java/com/hellobutler/app/AudioWorkflowPersistenceTest.kt`
- `client/app/src/main/AndroidManifest.xml`
- `client/app/src/main/java/com/hellobutler/app/ButlerApplication.kt`
- `client/app/src/main/java/com/hellobutler/app/core/AppContainer.kt`
- `client/app/src/main/java/com/hellobutler/app/data/local/DailyEventDao.kt`
- `client/app/src/main/java/com/hellobutler/app/data/remote/SyncApi.kt`
- `client/app/src/main/java/com/hellobutler/app/data/repository/DailyEventRepository.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/ButlerAudioPlaybackService.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/DailyEventExecution.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/DailyEventScheduler.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/DeferredSpeechNotification.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioExecution.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioResources.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioSequence.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioWorkflow.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioWorkflowScheduler.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/AudioWorkflowStore.kt`
- `client/app/src/main/java/com/hellobutler/app/execution/audio/SoundVoiceSettings.kt`
- `client/app/src/main/java/com/hellobutler/app/sync/ButlerMessagingService.kt`
- `client/app/src/main/java/com/hellobutler/app/sync/ButlerRequestWorkers.kt`
- `client/app/src/main/java/com/hellobutler/app/ui/App.kt`
- `client/app/src/main/java/com/hellobutler/app/ui/settings/SoundVoiceSection.kt`
- `client/app/src/main/java/com/hellobutler/app/ui/settings/UserSettingsScreen.kt`
- `client/app/src/main/java/com/hellobutler/app/ui/settings/UserSettingsViewModel.kt`
- `client/app/src/main/java/com/hellobutler/app/widget/WidgetNoteActivity.kt`
- `client/app/src/main/res/raw/volume_preview.mp3`
- `client/app/src/test/java/com/hellobutler/app/execution/audio/AudioWorkflowChecks.kt`
- `client/app/src/test/java/com/hellobutler/app/execution/audio/AudioWorkflowTest.kt`
- `docs/project_source_of_truth/PROJECT.md`
- `docs/project_source_of_truth/backend/BACKEND_ARCHITECTURE.md`
- `docs/project_source_of_truth/client/AUDIO_WORKFLOWS.md`
- `docs/project_source_of_truth/client/CLIENT_ARCHITECTURE.md`
- `docs/project_source_of_truth/client/CLIENT_WORKFLOW.md`
- `docs/implementation_reports/configurable_audio_experience.md` (this report)
