# Client Sync Flow

**Version:** 1.5  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `CLIENT_ARCHITECTURE.md`, and `CLIENT_WORKFLOW.md`

---

# Principle

Synchronization should require no normal user action. Room is the client's immediate working state. Backend state becomes authoritative after authenticated reconciliation.

---

# Two Butler Upload Paths

## Audio — Order / Talk

```text
record compressed audio
      ↓
release
      ↓
local placeholder = Sending...
      ↓
POST audio request
      ↓
202 + request_id
      ↓
accepted/handling
      ↓
placeholder = Sent • time
```

If upload cannot complete, retain the local recording/request and let WorkManager retry.

## Text

```text
user types/edits
      ↓
Send
      ↓
insert exact text locally + Sending state
      ↓
POST text request
      ↓
202 + request_id
      ↓
accepted/handling
      ↓
exact text remains + Sent state
```

Text does not use audio recording or STT. Both request types then share the same completion/reconciliation path.

---

# Completion Push

```text
backend saves completed canonical result
      ↓
FCM: butler_request_completed + request_id
      ↓
client fetches authenticated canonical result
```

FCM is a wake-up signal, not canonical response content and never transports response audio bytes.

---

# Foreground / Background Split

```text
                    FCM completed
                           │
             ┌─────────────┴─────────────┐
             │                           │
        App FOREGROUND              App BACKGROUND
             │                           │
     Coroutine / Repository          WorkManager
             │                           │
             └─────────────┬─────────────┘
                           ▼
                   Retrofit / OkHttp
                           │
                           ▼
                  GET canonical result
                           │
                           ▼
                      Room / cache
```

Both paths call the same repository/persistence logic.

---

# Final Result Reconciliation

For audio input:

```text
server transcript
      ↓
replace local Sending/Sent placeholder text
```

For text input:

```text
submitted text already canonical
      ↓
keep message text; reconcile delivery/result metadata
```

For both:

```text
server Butler response
      ↓
idempotent insert/update of one Butler message
      ↓
response audio reference
      ↓
start download immediately
      ↓
cache locally
```

Switching between notification and app must never create another copy of the response.

---

# Notification Publication

Foreground: persist then update active conversation.

Background/not visible: persist then publish notification using the same Butler message text.

Ordinary actions:

```text
speaker icon
phone/private-listen icon
Open in App
```

`Open in App` opens Main Screen with the Butler conversation overlay visible; there is no separate chat screen.

Playback actions may appear before audio caching completes and wait on the active download when needed.

---

# Audio Download / Cache

Response audio download begins as part of completion handling for both audio-input and text-input requests.

```text
completed result fetched
      ↓
audio reference available
      ↓
download/cache starts
      ↓
Room records status/path
```

Server response audio is retention-limited. Local cached audio is the normal historical playback source.

---

# Morning Brief / Good Night Summary

When synchronized/prepared content becomes due, automatically start Speak Aloud, expose Stop immediately, and retain Open in App. Prepare/cache content early enough when possible. Ordinary Butler responses remain silent by default.

---

# Direct Event Sync

Direct event changes remain local-first and independent of Butler requests:

```text
Room update → immediate UI → pending sync → WorkManager → backend reconciliation → Room
```

---

# Startup Convergence

```text
render usable Main Screen
+ enable Order/Talk recording
+ enable Text composition
      ↓
parallel background work
      ├── drain pending audio/text uploads
      ├── fetch pending completed requests
      ├── sync plan/events
      ├── refresh conversation
      └── reconcile schedules/audio
```

Correctness must not depend on manual Refresh.

---

# Retry / Idempotency

Client behavior must remain correct when FCM is delayed/duplicated, WorkManager retries, process dies during upload/download, foreground/background state changes, or results are fetched more than once.

Use stable request/message identifiers and idempotent Room upserts so one backend result becomes one local Butler message.

---

# User Experience Rule

The user sees simple messages and delivery states. Audio/text transport, FCM, WorkManager, synchronization, and caching remain implementation details.
