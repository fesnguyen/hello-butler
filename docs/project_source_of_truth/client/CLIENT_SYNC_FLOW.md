# Client Sync Flow

**Version:** 1.6  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `PROJECT_CLIENT.md`, and `PROJECT_CLIENT.md`

---

# Principle

Synchronization requires no normal user action. Room is the client's immediate working state; authenticated backend state becomes authoritative after reconciliation.

---

# Butler Requests

Audio (Order/Talk) and Text have different input transports but share one completion path:

```text
local Sending state
      ↓
POST audio/text → 202 + request_id
      ↓
backend processing
      ↓
FCM completed(request_id)
      ↓
foreground repository OR background WorkManager
      ↓
GET canonical result
      ↓
Room upsert
```

For audio, replace the temporary outgoing content with the server transcript. For Text, retain the exact submitted text and reconcile metadata. One backend result becomes one local Butler message.

FCM is only a wake-up signal. It never carries canonical response content or response-audio bytes.

---

# Text Before Audio

Canonical response text and speech reconcile independently:

```text
completed result
      ↓
persist/show text immediately
      ↓
audio_status
   ├── ready       → download/cache
   ├── pending     → enqueue/retry reconciliation
   └── unavailable → keep text usable
```

Playback UI maps the local/backend state to **Loading**, **Ready**, or **Speaking/Stop**. Playback selected while speech is pending enters Loading and waits/reconciles rather than issuing duplicate generation requests.

Both Listen Aloud and Phone Listen use the same cached audio asset. Only routing/playback state differs.

---

# Direct Event Sync

Direct event changes remain local-first:

```text
Room update → immediate UI → pending sync → WorkManager → backend reconciliation → Room
```

Upcoming Event changes use the same synchronization principles but mutate their authoritative User Context rather than an Upcoming Event table.

---

# Notes & Preferences Sync

Notes/preferences shown in User Settings are a projection of user-manageable authoritative User Context, not a separate client data source.

```text
Backend User Context
      ↓
list/sync contract
      ↓
Repository
      ↓
Room/cache
      ↓
User Settings
```

The flow is bidirectional:

```text
Butler remembers preference ─┐
                            ├→ backend authoritative mutation
User Settings add/edit ─────┘
                                      ↓
                              reconciliation/sync
                                      ↓
                                    Room
                                      ↓
                               User Settings UI
```

After a Butler interaction reports a relevant User Context/preference mutation, schedule or trigger saved-context reconciliation. Direct User Settings add/edit/delete mutations update the backend and reconcile Room after success. Startup/background sync also reconciles the list so interrupted updates converge without manual Refresh.

Use stable identifiers and idempotent Room upserts. Editing/deleting a preference must update what Butler sees on later interactions; an ordinary note must not silently become a personalization preference.

Do not sync every User Context row into the User Settings list. The backend/API projection determines which note/preference records are user-manageable; routines, temporary/one-time planning context, Upcoming Events, and internal metadata remain excluded.

---

# Startup Convergence

```text
render usable Main Screen
+ enable Order/Talk/Text
      ↓
parallel background work
      ├── drain pending uploads/mutations
      ├── fetch completed requests
      ├── sync plan/events
      ├── sync user-manageable notes/preferences
      ├── refresh conversation
      └── reconcile schedules/audio
```

Correctness must not depend on manual Refresh.

---

# Retry / Idempotency

Behavior must remain correct when FCM is delayed/duplicated, WorkManager retries, the process dies during upload/download/sync, foreground/background state changes, or the same result is fetched repeatedly.

Use stable identifiers and idempotent Room/repository operations so all surfaces converge on backend-authoritative state.

---

# Morning Brief / Good Night Summary

Scheduled speech uses the shared backend TTS result. Prepare/cache it early when possible; when playback starts, expose Stop immediately. Ordinary Butler responses remain silent by default.
