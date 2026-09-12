# Client Workflow

**Version:** 1.5  
**Status:** Source of Truth  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `CLIENT_ARCHITECTURE.md`

---

# Main Screen

Hello Butler has one primary product screen:

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

Butler interaction happens over the user's day. There is no separate conversation screen.

When `Open in App` is selected from a notification, open Main Screen and show the Butler conversation overlay from the bottom at roughly 60–70% screen height as appropriate to the current UI.

---

# Conversation Overlay

Recent conversation remains visible while the user interacts. Messages should be visually compact and close together.

For Butler audio responses, duration belongs beside the role/title, for example:

```text
Butler · 0:08
Got it. I've moved your meeting to 4 PM.
[ speaker icon ] [ private-listen icon ]
```

Playback icons sit directly under the response text rather than in a large separate action area.

---

# Order / Talk Recording

```text
press and hold Order or Talk
        ↓
small recording animation in user's message area
        ↓
conversation remains readable
        ↓
release
        ↓
local outgoing entry = Sending...
        ↓
compressed audio upload
```

No extra End button or `Release to send` instruction is required.

After backend acceptance/handling:

```text
Sending... → Sent • <time>
```

After completion, replace the temporary content with the backend transcript. Do not show historical playback for the user's own audio.

---

# Text Workflow

Text is now direct typed composition.

```text
tap Text
      ↓
text composer appears/enables inside existing conversation overlay
      ↓
user types or edits
      ↓
Send
      ↓
exact typed text appears as user's message
      ↓
request state = Sending...
      ↓
backend accepts → Sent
      ↓
shared Butler processing completes
      ↓
Butler response appended
```

Text uses no microphone, no local STT, no backend STT, and no intermediate server-generated draft.

The user can edit freely before pressing Send. Once sent, the typed message follows the same asynchronous request/result lifecycle as Order/Talk.

---

# One Shared Result Experience

Whether input was audio or typed text, completion results in:

```text
You
<transcript OR submitted text>

Butler · <audio duration>
<canonical response text>
[speaker icon] [private-listen icon]
```

The Butler response becomes normal conversation history. There is only one canonical response.

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

The same completion path applies to audio and text requests.

---

# Foreground vs Background

If foreground, persist the result and update the active conversation.

If background/closed, persist the same result and show a notification with the same Butler response text. Opening later must not generate a second response.

---

# Ordinary Butler Notification

```text
Hello Butler
<canonical response text>
[speaker icon] [private-listen icon] [Open in App]
```

Audio download starts immediately after completion fetch. The notification may appear before caching finishes; selecting playback waits for the active download if necessary.

---

# Playback

Speaker icon plays outward through the normal speaker route. Phone icon provides private/call-style listening; it is not a real network call or realtime Butler session.

Both actions use the same cached Butler response audio.

---

# Morning Brief / Good Night Summary

These are proactive-speech exceptions:

```text
content due
   ↓
notification/execution surface
   ↓
automatically Speak Aloud
   ↓
Stop available immediately
   ↓
Open in App retained
```

Ordinary Butler messages do not auto-speak.

---

# Instant Startup

```text
user taps app icon
      ↓
render usable Main Screen shell
      ↓
Order/Talk recording + Text composer interaction available immediately
      ↓
background loading continues
      ├── Room
      ├── plan sync
      ├── conversation sync
      ├── pending responses
      └── reconciliation
```

Do not make interaction wait for full synchronization.

---

# Offline

Order/Talk recordings can be queued for later upload. Typed requests can be queued similarly when network is unavailable. The UI keeps pending state understandable without requiring the user to remain in the app.

---

# Direct Event Configuration

Direct event edits remain deterministic/local-first and bypass Butler AI: update Room, update UI, queue sync, then reconcile with backend.

---

# Guiding UI Principle

Butler should feel continuously present without becoming visually heavy. Audio and typed input differ only in how the user sends the message; the conversation, completion, notification, response audio, and history experience remain unified.
