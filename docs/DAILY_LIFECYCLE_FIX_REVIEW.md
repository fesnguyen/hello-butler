# Daily lifecycle review fixes

Branch: `fix/daily-lifecycle-review`
Base: `feature/full-daily-lifecycle` at `e6ecc8f493abaae04b1d681913a5ef73a8824c6d`
Review request: `hello_butler_full_daily_lifecycle_review_and_fix_request(1).md`
Date: 2026-09-08

Implementation is committed for review. **The attachment's full Definition of Done
is not yet verified: Android builds, physical-device scenarios, real FCM delivery
and PostgreSQL concurrency validation remain outstanding. Do not treat this as a
merge-ready certification.**

## Changes and decisions

| Review item | Implementation |
| --- | --- |
| Flush before reasoning | `ButlerRepository.send` drains the event outbox first. Failed uploads prevent the AI request. The AI request does not hold the event-edit mutex. |
| Reconcile after reasoning | Authenticated Butler results include affected `plan_dates`, including both dates when an event moves. Today, tomorrow and those dates are queued durably and pulled immediately. Failed reconciliation preserves the successful command response and reports sync pending. |
| Consistent silent push | `DailyPlanChanges.transaction` observes canonical ORM changes through flushes, commits, closes the session, then publishes. Butler actions, explicit preparation, evening preparation and all seven outbox mutations use this boundary. Rollbacks and unchanged conflict/duplicate transactions do not publish. |
| Partial workflow success | A committed event still produces a hint if subsequent conversation-history storage fails. Tomorrow's committed plan still produces a hint if summary persistence fails. Evening preparation can emit two hints; Android appends a follow-up sync when a pass is already running. |
| FCM registration | Login/register/Google acceptance, saved-session restoration, authenticated process startup and `onNewToken` request current-token registration. The old Main Screen trigger was already an indirect path; it is replaced by explicit session ownership. Missing Firebase configuration is a safe no-op. |
| Logout | Registration and unregistration share the session guard. Unregister uses credentials before revocation and has a five-second limit. Registration cleans up rotated tokens. Credentials are cleared before in-flight sync is drained and Room/schedules are cleared. Speech is stopped. Offline unregistration is best effort. |
| Modern Android speech | Exact alarms retain automatic Room-backed foreground TTS. Delayed WorkManager **never starts a foreground service**: it posts a Listen notification. User interaction starts the existing mediaPlayback service. The app explains the limitation and links to alarm access. |
| Playback safety | Claims require planned status and matching cached date/time/content/version. Stale, skipped, cancelled and already-claimed events cannot play. Startup/reboot/package/clock/access restoration preserves future scheduling. TTS selects an installed offline voice, uses audio focus, and has a bounded CPU wake lock. Stop and cancellation release resources. |
| Timezone | Existing architecture has one backend planning timezone, local date/time payloads and device-zone interpretation. All device zones must equal `BUTLER_DEFAULT_TIMEZONE`; per-user zone support is deferred. |

No persistence schema migration is required. `plan_dates` is an additive HTTP
response field; FCM still contains only `type=daily_plan_changed`. Existing
canonical-wins conflicts, durable operation IDs, planner ownership protection
and local-first editing remain in place. Post-commit publication is bounded by
`PUSH_TIMEOUT_SECONDS` (default 5), so a stalled push provider cannot hold the
canonical response indefinitely.

Regression testing also found a pre-existing `MissingGreenlet` failure when sync
serialized an UPDATE-expired `updated_at`. Sync now explicitly awaits an ORM
refresh before returning canonical event state.

## Speech fallback contract

Without exact-alarm access, **automatic, precisely scheduled background speech is
not promised**. WorkManager may deliver a local notification late. The user must
tap Listen, and notification permission/channel access must be enabled. Playback
then uses Room and an installed offline voice, with the normal ongoing
notification and immediate Stop. Neither network nor Firebase is needed.

If notifications are disabled, the worker logs the reason and retries without
claiming playback. Previous local-day speech is not replayed later. Force-stop
suppresses Android work until another manual launch. Reboot testing should
include first unlock: Room is stored in credential-protected storage.

An ordinary delayed worker cannot assume the exact-alarm foreground-service
exemption. Replacing a direct start with another unverified foreground-worker
start would not establish that exemption. This implementation uses the explicitly
documented notification-interaction exemption instead. It exposes the loss of
unattended playback rather than claiming equivalent service.

Platform references:

- [Android foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android alarm timing and permissions](https://developer.android.com/develop/background-work/services/alarms)
- [Foreground-service types and permissions](https://developer.android.com/about/versions/14/changes/fgs-types-required)

## Configuration

For Vietnam devices using `Asia/Ho_Chi_Minh`, configure:

```dotenv
BUTLER_DEFAULT_TIMEZONE=Asia/Ho_Chi_Minh
EVENING_PREPARATION_TIME=22:30
```

Use the same timezone on every device. The server's default is UTC, and the
Android evening trigger remains 22:30 device time. Cross-zone travel or changing
only one side is outside the supported initial deployment.

Offline logout may leave an old token on the backend until Firebase invalidation
cleanup or the next registration reassigns it. Signed-out sync performs no
authenticated fetch. No account credentials are retained to retry unregistration.

## Verification performed

| Check | Result |
| --- | --- |
| `uv sync --offline --frozen` | Passed with installed dependencies. |
| `uv run ruff check app tests` | Passed. |
| `uv run ruff format --check app tests` | Passed. |
| `uv run python -m unittest discover -s tests -v` | **12 passed** against real SQLAlchemy async transactions with SQLite. AI and FCM are fakes. |
| Pyright with the project virtualenv | 11 diagnostics; the same 11 exist at the base commit. No new diagnostics. Existing issues concern LangGraph typing and Google token verification typing. |
| `git diff --check` | Passed. |
| Android build/test attempt | Blocked downloading the Gradle 9.3 distribution: network unreachable. No Android SDK, emulator or attached device was available. |
| Android instrumentation suite | Added seven tests; **not compiled or executed here**. |

Backend tests exercise committed data visible to both device-token recipients,
rollback suppression, non-fatal provider/lookup failures and timeouts, all seven mutations,
duplicates, stale versions, actual-day input before evening preparation,
planner ownership protection, affected dates, partial workflow failures and
owner-scoped unregistration. SQLite does not validate PostgreSQL row/advisory
locks or concurrent transaction behavior; evening lock acquisition is stubbed.

Android tests use real Room, session storage and repositories with fake HTTP/FCM
token providers. They cover immediate edit → reasoning ordering, failed-upload
retention, local edits during reasoning, canonical-wins reconciliation,
successful-command/failed-pull handling, pre-existing token registration after
login/restoration, logout and atomic playback claims.

## Remaining lifecycle gate

Run on a disposable debug installation: instrumentation tests clear its session
preferences. Configure a test backend, a Firebase-enabled build and two test
devices for delivery scenarios.

```bash
cd backend
uv sync --frozen
uv run python -m unittest discover -s tests -v
uv run ruff check app tests
uv run ruff format --check app tests
uv run pyright

cd ../client
./gradlew :app:assembleDebug :app:connectedDebugAndroidTest
```

| Scenario | Required real-system evidence before merge |
| --- | --- |
| A — edit then reason | Move Exercise 18:00 → 19:00 and immediately ask Butler about it. Verify the upload precedes /talk, AI context uses 19:00 and Room contains the resulting canonical version. Interrupt the post-response pull and confirm retry without repeating the command. |
| B — two devices | Register A and B to the same test account. Mutate via Butler, explicit planning, evening preparation and direct sync. Verify B receives only a data hint, authenticates its pull, updates Room and changes its execution schedule. Send a second mutation during an active pull. |
| C — existing token | Generate a token while signed out, log in without token rotation, verify the backend row; restart with a saved session and verify again. Test rotation, logout and a build without Firebase configuration. |
| D — offline speech | On API 31, 34, 35 and 36, cache a spoken event and an offline voice. Grant exact alarms, disconnect network, close UI and lock device. Verify speech, foreground notification, audio focus and immediate Stop. |
| E — no exact alarms | Revoke alarm access, relaunch to restore fallback scheduling, then close/lock/offline. Verify a delayed Listen notification, no worker-launched service, and Room speech after tapping Listen. Disable notifications and verify no claim is consumed. This path requires user action. |
| F — reboot | Cache future spoken events plus completed/skipped/old controls. Reboot and unlock once. Verify only eligible future events are scheduled and controls do not play; verify exact and notification paths separately. |
| G — PostgreSQL conflict | Race two operations with the same base_version against PostgreSQL. Verify one applied and one canonical conflict, no infinite outbox retry, and Room/alarm convergence. Repeat planner regeneration after user modification. |

Useful evidence: timestamped backend requests and operation IDs, Room/outbox
inspection, `adb shell dumpsys alarm`, `adb shell dumpsys jobscheduler`, and
`adb logcat` around HelloButlerTTS/HelloButlerPush. Keep authentication and
registration tokens out of shared logs.
