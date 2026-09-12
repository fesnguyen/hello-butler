# Client Sync Flow

**Version:** 1.4  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `CLIENT_ARCHITECTURE.md`, and `CLIENT_WORKFLOW.md`

---

# Principle

Synchronization should require no normal user action.

Room is the client's immediate working state. Backend state becomes authoritative after authenticated reconciliation.

---

# Direct Event Sync

Direct event changes remain local-first:

```text
edit / complete / skip / delay / cancel
        ↓
Room updated immediately
        ↓
UI updates immediately
        ↓
pending sync operation recorded
        ↓
WorkManager uploads when possible
        ↓
backend validates / reconciles
        ↓
Room updated with canonical result
```

This path is independent from Butler audio requests.

---

# Butler Audio Request Upload

```text
record compressed audio
      ↓
release
      ↓
local placeholder = Sending...
      ↓
POST /api/butler/requests
      ↓
202 + request_id
      ↓
server accepted/handling signal
      ↓
local placeholder = Sent • time
```

If upload cannot complete, retain the local pending request and let WorkManager retry when connectivity returns.

---

# Completion Push

Push is a wake-up signal, not the canonical response payload.

```text
backend saves completed Butler result
      ↓
FCM: butler_request_completed + request_id
      ↓
client fetches authenticated canonical result
```

Do not transport response audio bytes in FCM.

---

# Foreground / Background Split

```text
                    FCM completed
                           │
             ┌─────────────┴─────────────┐
             │                           │
        App FOREGROUND              App BACKGROUND
             │                           │
             ▼                           ▼
     Coroutine / Repository          WorkManager
             │                           │
             └─────────────┬─────────────┘
                           ▼
                   Retrofit / OkHttp
                           │
                           ▼
             GET canonical response + audio
                           │
                           ▼
                      Room / cache
```

Both paths must call the same repository/persistence logic so the final conversation state is identical.

---

# Final Result Reconciliation

After canonical result fetch:

```text
server user transcript
      ↓
replace local Sending/Sent placeholder text

server Butler response
      ↓
insert/update one Butler conversation message

response audio URL/reference
      ↓
start audio download immediately
      ↓
cache locally
```

The user does not get a second copy of the response when switching between notification and app.

---

# Notification Publication

If the app is foreground, update the active conversation immediately.

If the app is background/not visible, persist first and publish a notification using the same Butler message text.

Ordinary Butler notification actions:

```text
speaker icon
phone/private-listen icon
Open in App
```

`Open in App` should always be retained.

The notification may appear before the audio file finishes caching. Playback actions wait on the active download if necessary.

---

# Audio Download / Cache

Audio download begins as part of completion handling, not after the user asks to play it.

```text
completed result fetched
      ↓
audio reference available
      ↓
download/cache starts
      ↓
Room/cache records status/path
```

If the app/process is backgrounded, WorkManager owns retryable completion/audio work.

Server response audio is retention-limited. Local cached audio is the normal source for historical playback.

---

# Morning Brief / Good Night Summary Delivery

Morning Brief and Good Night Summary are proactive-speech exceptions.

When scheduled content becomes due:

```text
local/synchronized content ready
      ↓
notification/execution surface
      ↓
automatically start Speak Aloud
      ↓
ongoing playback exposes Stop
      ↓
notification retains Open in App
```

The user can stop playback at any time.

If audio/content needs network retrieval, the client should prepare/cache it early enough when possible. Existing offline/local fallback behavior may remain where it improves reliability.

---

# Ordinary Server-to-Client Sync

Daily Plan changes continue to use silent synchronization hints:

```text
backend state changes
      ↓
FCM daily_plan_changed
      ↓
client authenticated sync
      ↓
Room
      ↓
reconcile alarms / notifications / execution
```

Routine synchronization should stay invisible.

---

# Background Responsibilities

```text
Coroutine/Repository  immediate foreground fetch and persistence
WorkManager           retryable background network work
FCM                   wake-up/completion/change hints
Notification          user-facing result/reminder surface
AlarmManager          exact local execution when required
Room                  durable bridge between network, UI, and execution
Audio cache           durable device-side Butler playback
```

Do not keep a long-lived background service merely to remain synchronized.

---

# Startup Convergence

Startup must prioritize immediate interaction, then converge data in the background:

```text
render usable shell + enable recording
      ↓
parallel background work
      ├── drain pending uploads
      ├── fetch pending completed requests
      ├── sync plan/events
      ├── refresh conversation
      └── reconcile schedules/audio
```

Correctness must not depend on the user pressing manual Refresh.

---

# Retry / Idempotency

Client work must remain correct when:

- FCM is delayed or duplicated
- WorkManager retries
- process dies during download
- foreground/background state changes during fetch
- result is fetched more than once

Use stable request/message identifiers and idempotent Room upserts so one backend result becomes one local Butler message.

---

# User Experience Rule

Synchronization, completion fetching, and audio caching should be invisible during normal operation. The user sees simple conversation states and notifications, not transport machinery.
