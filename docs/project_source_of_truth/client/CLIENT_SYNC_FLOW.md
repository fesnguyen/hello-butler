# Client Sync Flow

**Status:** Implemented
**Authority:** Derived from `PROJECT.md`, `CLIENT_ARCHITECTURE.md`, and `CLIENT_WORKFLOW.md`

---

# Principle

Synchronization should require no normal user action.

```text
User action   → local effect now → sync in background
Server change → silent push      → client refreshes automatically
Important information            → user notification
Scheduled event                  → local AlarmManager / TTS
```

Room remains the client's immediate working state. The backend becomes authoritative after successful synchronization.

---

# Client → Server

Direct event changes are local-first.

```text
Edit / complete / skip / delay / cancel
        ↓
Room updated immediately
        ↓
UI updates immediately
        ↓
Pending sync operation recorded
        ↓
WorkManager attempts upload when network is available
        ↓
Backend validates and returns canonical state
        ↓
Room reconciled and pending operation removed
```

Sync must not block ordinary event editing. Failed uploads remain pending and retry automatically. Opening the authenticated app and important background preparation should also drain pending changes before pulling newer server state.

The implemented outbox covers create, edit, complete, skip, delay, cancel, and
delete. Operations are coalesced per event while retaining the original base
version and stable operation ID. A conflict accepts returned canonical server
state; a successful or duplicate result reconciles Room before removing the
pending operation.

---

# Server → Client

Push is a wake-up signal, not the canonical payload.

```text
Backend state changes
        ↓
Silent push: what changed / what should refresh
        ↓
Client performs authenticated sync
        ↓
Room updated
        ↓
Local alarms, notifications, and TTS schedules reconciled
```

A visible notification is reserved for information that requires or deserves user attention. Routine synchronization should stay silent.

FCM registration is authenticated. The data message contains only
`type=daily_plan_changed`; receiving it enqueues the same constrained sync worker
used by local edits. Startup, six-hour periodic work, connectivity-constrained
retry, boot restoration, and evening preparation provide convergence when a
push is delayed or missed.

---

# Background Responsibilities

```text
WorkManager   deferred/retryable network work and sync reliability
Push          prompt server-originated wake-up / refresh signal
Notification  intentional user-facing interruption
AlarmManager  time-sensitive local execution
Room          durable bridge between synchronization and execution
```

Do not keep a long-lived background service merely to stay synchronized.

---

# Evening Preparation Example

For an initial 23:00 sleep schedule:

```text
22:30 WorkManager wakes
        ↓
push pending client changes
        ↓
backend now has today's latest reality
        ↓
generate Good Night Summary
        ↓
prepare tomorrow + Morning Brief
        ↓
pull today + tomorrow
        ↓
store in Room
        ↓
schedule local execution
        ├── 22:45 Good Night Summary → TTS
        └── tomorrow Morning Brief   → TTS
        ↓
WorkManager finishes
```

The scheduled TTS events execute from Room and should not require backend access at playback time. If preparation completes asynchronously or server state changes later, a silent push can wake the client to refresh and reschedule.

---

# Sync Ordering

Whenever synchronization may precede backend reasoning about the user's day:

```text
PUSH pending local changes
        ↓
backend reasoning / preparation
        ↓
PULL canonical server state
        ↓
Room reconciliation
        ↓
local execution reconciliation
```

This ordering prevents Good Night Summary, replanning, and similar workflows from reasoning over stale client state.

---

# User Experience Rule

Ordinary synchronization is invisible. Do not show blocking sync screens or require a manual refresh for normal operation. Manual refresh may remain as a recovery/development action, but correctness must not depend on the user remembering to use it.
