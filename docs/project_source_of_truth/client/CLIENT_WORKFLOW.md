# Client Workflow

**Version:** 1.4  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `CLIENT_ARCHITECTURE.md`

---

# Purpose

This document defines the user-facing Android flows for Daily Events, Butler interaction, notifications, audio playback, and background delivery.

---

# Main Screen

The Main Screen remains the primary product surface.

```text
MainScreen
│
├── DailyEventList
├── ButlerConversationOverlay
└── ButlerControlBar
    ├── Order
    ├── Talk
    └── Text
```

Butler interaction should happen on top of the user's day rather than navigating to a separate chat screen.

---

# Butler Conversation Overlay

The conversation overlay shows persistent recent conversation while leaving the Daily Event list visible.

Recording should not replace the conversation with a dedicated voice screen.

```text
conversation history
      ↓
user holds Order/Talk
      ↓
small recording animation appears in user's message area
      ↓
conversation remains readable
```

No extra end button or explicit `Release to send` label is required. Releasing the existing hold gesture ends recording and starts send behavior naturally.

---

# Order / Talk Recording

```text
Press and hold Order or Talk
        ↓
small listening animation
        ↓
user speaks
        ↓
release
        ↓
local outgoing entry = Sending...
        ↓
compressed audio upload begins
```

The user can continue using the app immediately after release.

---

# Sending to Sent

After release, the user's temporary conversation entry represents transport state rather than transcript text.

```text
Sending...
   ↓
backend accepted / handling signal received
   ↓
Sent • 10:14 AM
```

Do not show the user's waveform/audio as a historical playable message.

When the final Butler result arrives, replace the temporary `Sent` content with the backend-produced transcript.

Example:

```text
Before completion
You
Sent • 10:14 AM

After completion
You
hello tonight I have a meeting with John at 8:00 p.m.
```

---

# Butler Processing

While the request is in progress, the conversation may show a minimal Butler activity indicator such as:

```text
Butler
••• Thinking...
```

Avoid large processing screens, progress wizards, or multiple intermediate cards.

---

# Completed Response

One completed Order/Talk interaction results in:

```text
You
<server transcript>

Butler
<canonical response text>
[ speaker icon ] [ phone/private-listen icon ]
```

The response becomes normal conversation history. It is not a temporary separate late-response overlay.

If Butler changed the user's day, the Daily Event list behind the conversation should reconcile through the normal data/sync path.

---

# Same Message in Foreground and Background

There is only one Butler response.

## App foreground

The fetched result is persisted, then the active conversation updates immediately.

## App background / closed

The fetched result is persisted, then Android shows a notification containing the same Butler response text.

Opening the application later shows the same persisted message already present in conversation history.

Do not generate a second response when opening the app.

---

# FCM Completion Flow

```text
FCM completed(request_id)
          │
    ┌─────┴─────┐
    │           │
foreground   background
    │           │
coroutine    WorkManager
repository
    │           │
    └─────┬─────┘
          ↓
GET canonical result
          ↓
Room persistence
          ↓
start audio cache download
          ↓
active conversation OR notification
```

The client should immediately fetch/cache audio after receiving completion rather than waiting for a playback button press.

---

# Ordinary Butler Notification

The notification shows the same canonical Butler response text.

Actions:

```text
[ speaker icon ] [ phone/private-listen icon ] [ Open in App ]
```

The notification may appear while response audio is still downloading.

If the user selects an audio action before caching finishes:

```text
action tapped
   ↓
wait on current audio download
   ↓
play as soon as ready
```

Do not require another explicit `Audio ready` confirmation step.

`Open in App` remains available so the user can view the response within conversation context.

---

# Speak Aloud

The speaker icon plays the Butler response through the normal outward speaker route.

```text
speaker icon
   ↓
ensure audio available
   ↓
request appropriate audio focus
   ↓
play
   ↓
allow Stop
```

---

# Private / Receive-as-Call Listening

The phone icon means a private, call-like listening presentation.

It is not a real network phone call and not a realtime session with Butler.

```text
phone icon
   ↓
ensure response audio available
   ↓
route/present as private listening
   ↓
user listens like receiving a short Butler call
   ↓
end / stop
```

Exact Android audio-routing details may be refined during implementation while preserving this product behavior.

---

# Morning Brief

Morning Brief is a proactive-speech exception.

At its scheduled delivery time:

```text
Morning Brief due
    ↓
show notification / execution surface
    ↓
automatically begin Speak Aloud
    ↓
user may Stop at any time
```

The notification/surface retains `Open in App` so the user can read the Morning Brief in context.

---

# Good Night Summary

Good Night Summary follows the same proactive pattern:

```text
Good Night Summary due
    ↓
show notification / execution surface
    ↓
automatically begin Speak Aloud
    ↓
user may Stop at any time
```

The notification/surface retains `Open in App`.

---

# Default Speech Behavior

```text
Morning Brief       → automatically Speak Aloud; Stop available
Good Night Summary  → automatically Speak Aloud; Stop available
Ordinary Butler     → text first; no automatic speech
```

Ordinary Butler messages expose playback icons for user choice.

---

# Text Workflow

Text is context-aware speech-to-editable-text preparation.

```text
Press and hold Text
      ↓
record audio
      ↓
release / upload
      ↓
backend understands speech
      +
relevant user preference
      +
recent conversation
      ↓
rewrite / translate / normalize when useful
      ↓
editable text appears
      ↓
user edits / copies / uses text
```

Text should not automatically perform an Order/Talk action.

---

# Instant Startup Workflow

```text
user taps app icon
      ↓
render usable Main Screen shell
      ↓
Order/Talk/Text hold-to-record available immediately
      ↓
background loading continues
      ├── Room content
      ├── plan sync
      ├── conversation sync
      ├── pending responses
      └── other reconciliation
```

Do not make voice capture wait for full Daily Plan/history synchronization.

---

# Offline Voice Workflow

```text
user records while offline
      ↓
recording stored locally
      ↓
request queued
      ↓
connectivity returns
      ↓
WorkManager uploads
      ↓
normal Sending / Sent / completion flow resumes
```

The UI should make pending state understandable without forcing the user to stay in the app.

---

# Direct Event Configuration

Direct event editing remains deterministic and local-first.

```text
tap event menu
    ↓
edit / delay / skip / complete / cancel
    ↓
Room updates immediately
    ↓
UI updates immediately
    ↓
sync queued
```

Do not route obvious direct event edits through Butler AI.

---

# Notification Principle

Notifications are presentation/wake-up surfaces, not a separate product state.

A Butler result shown as a notification must correspond to the same persisted conversation message the user sees after opening the app.

Morning Brief and Good Night Summary may auto-play; ordinary Butler notifications do not.

---

# Complete UI Mental Model

```text
                         MAIN SCREEN
                             │
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
     DAILY EVENTS       CONVERSATION        CONTROL BAR
                             │           Order / Talk / Text
                             │
                ┌────────────┼────────────┐
                ▼            ▼            ▼
             recording    Sending/Sent  final messages
                │                           │
          small animation              text + audio icons
```

---

# Guiding UI Principle

Butler should feel continuously present without becoming visually heavy.

The user should be able to speak, leave, return, read, listen aloud, listen privately, or open a notification in the app without encountering different copies of the same interaction.
