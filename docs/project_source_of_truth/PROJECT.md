# Personal Butler

**Document:** Project Overview  
**Version:** 1.5  
**Status:** Source of Truth

---

## Vision

Hello Butler is a proactive AI companion that helps users organize, prepare, execute, and adapt their daily life while feeling like one person accompanying them throughout the day.

The product should reduce the mental effort required to remember, organize, and execute daily life while leaving decisions and control with the user.

---

## Core Product Model

```text
User Context
      ↓
Nightly Planning
      ↓
Tomorrow's Daily Events
      ↓
Morning Brief
      ↓
Daily Execution
      ├── reminders
      ├── user actions
      ├── Butler conversations
      └── plan changes
      ↓
Reality Tracking
      ↓
Good Night Summary
      ↓
Prepare Tomorrow
```

Daily Events remain the concrete representation of the user's day. User Context describes durable, recurring, temporary, or one-time information that helps Butler prepare and adapt that day.

---

## Three Primary Butler Controls

The client exposes three persistent controls:

```text
Order
Talk
Text
```

They are three ways to interact with one Butler, not three assistants and not three reasoning systems.

```text
Order → recorded audio; handle this for me; I may leave immediately
Talk  → recorded audio; I am actively talking with Butler
Text  → typed/editable text; send what I wrote to Butler
```

Order and Talk use recorded audio as their normal input transport. Text uses direct typed text and does not record audio or use STT.

The input transport and Butler reasoning are separate concerns. Audio and text are normalized into the same Butler application/graph workflow after audio transcription.

---

## One Butler Workflow, Two Input Transports

```text
                    USER
                     │
          ┌──────────┴──────────┐
          │                     │
     Order / Talk              Text
          │                     │
     record audio           type/edit text
          │                     │
      audio API               text API
          │                     │
      transcribe                  │
          └──────────┬───────────┘
                     ↓
             normalized message
                     ↓
              Butler workflow
                     ↓
        command / query / clarify
                     ↓
             tools / DB / state
                     ↓
        one canonical Butler result
                     ↓
              text + audio
```

Do not create separate Audio and Text Butler graphs. Once an audio request has been transcribed, downstream reasoning should not need to care whether the normalized message originated from audio or typed text except where provenance is operationally useful.

---

## Recorded Voice, Not Live Voice

Hello Butler does not use a continuous live-call conversation model for normal interaction.

For Order and Talk:

```text
press and hold
      ↓
record compressed audio locally
      ↓
release
      ↓
upload complete audio request
      ↓
backend understands speech + intent
      ↓
execute / answer / clarify
      ↓
produce one Butler response
```

Conversation history remains visible while recording. Recording is represented by only a small animation in the user's message area. Do not replace the conversation with a full-screen recorder or multi-step voice wizard.

---

## Audio Outgoing State

After release, the client immediately creates a local outgoing conversation entry:

```text
release
  ↓
Sending...
  ↓
backend accepts request / handling signal arrives
  ↓
Sent • <time>
  ↓
final result arrives
  ↓
replace temporary Sent content with server transcript
```

The user has no normal need to replay their own recorded request. The final visible user message is transcript text, not an audio player.

---

## Text Interaction

Text is direct composition, not speech-to-editable-text.

```text
tap Text
   ↓
show/enable composer inside existing Butler conversation overlay
   ↓
type or edit text
   ↓
Send
   ↓
POST text request
   ↓
normal Butler request lifecycle
   ↓
Butler workflow
   ↓
canonical Butler response text + audio
```

Text does not use microphone capture, local STT, or backend transcription. The user's typed text is already the canonical user-side message content.

Text should use the same asynchronous request/result model as Order/Talk so process death, background completion, FCM, Room persistence, notifications, audio caching, and conversation history do not need separate implementations.

Text is a client input method, not a third semantic intent. Semantic intent remains command, query, or clarify. Unless a future product requirement says otherwise, typed Text enters the normal conversational/Talk expectation while the model still infers semantic intent from the message.

---

## One Interaction, One Conversation Result

A completed Butler interaction becomes:

```text
one user message
+
one Butler message
```

For audio input, the user message is the final transcript. For text input, it is the exact submitted text.

The Butler message contains canonical response text and associated response audio. There is no separate in-app response and notification response. A notification is only another presentation surface for the same Butler message.

---

## Asynchronous Delivery

Both input transports share one request/result lifecycle:

```text
POST audio OR POST text
      ↓
backend creates request
      ↓
202 + request_id
      ↓
backend continues asynchronously
      ↓
normalize input
      ├── audio → transcribe
      └── text  → use submitted text
      ↓
Butler reasoning / tools / DB
      ↓
response text + response audio
      ↓
save canonical result
      ↓
FCM completed(request_id)
      ↓
client fetches canonical result
```

FCM is a wake-up/completion signal, not the canonical response payload.

---

## Foreground and Background Completion

```text
                    FCM completed
                         │
            ┌────────────┴────────────┐
            │                         │
       app foreground            app background
            │                         │
   coroutine / repository         WorkManager
            │                         │
            └────────────┬────────────┘
                         ↓
                 Retrofit / OkHttp
                         ↓
              GET canonical result
                         ↓
                   Room / cache
```

Foreground/background only changes how Android executes work. It must not change the product result.

---

## Response Audio

Butler response audio is generated by the backend-side AI/audio pipeline and associated with the canonical Butler message regardless of whether input was audio or text.

The client starts fetching/caching response audio immediately after completion. A Butler message exposes compact playback actions:

```text
speaker icon → play aloud
phone icon   → private/call-style listening
```

If playback is selected before download finishes, wait for the in-progress download and play when ready.

---

## Notification Behavior

Ordinary Butler-response notifications contain the same canonical response text and expose:

```text
speaker icon
phone / private-listen icon
Open in App
```

The notification does not carry the audio binary. `Open in App` opens the single Main Screen with the Butler conversation overlay visible; there is no separate conversation screen.

---

## Morning Brief and Good Night Summary

Morning Brief and Good Night Summary are proactive-speech exceptions. When due, they automatically begin Speak Aloud playback. The user must always be able to Stop immediately, and the notification retains `Open in App`.

Ordinary Butler responses remain silent by default.

---

## Audio Storage and Retention

```text
Client
- stores Butler response audio locally for conversation playback

Backend
- stores response audio for limited retention
- removes old response audio regularly
- treats uploaded user audio as temporary processing data
```

Conversation text is the durable cross-device record. After reinstall, old text may be restored while old audio may no longer exist after server retention expires.

---

## Instant App Startup

The app must become usable immediately after launch.

```text
APP LAUNCH
    │
    ├── critical path
    │     ├── render Main Screen shell
    │     ├── enable Order/Talk recording immediately
    │     └── make Text composer interaction immediately available
    │
    └── background path
          ├── load/sync Daily Plan
          ├── load conversation history
          ├── fetch pending results
          └── reconcile other data
```

Order/Talk recording must not wait for network synchronization. Offline audio requests may be queued for later upload. Typed requests may likewise be queued when appropriate.

---

## Backend and Client Boundary

### Backend owns

- Butler reasoning
- speech understanding for uploaded Order/Talk audio
- normalization into one Butler workflow
- user/context-aware interpretation
- Daily Plan / Daily Event authority after synchronization
- domain actions and validation
- conversation text history
- response text and response audio generation
- temporary server audio retention
- completion push publication

### Client owns

- immediate Main Screen UI and conversation overlay
- Order/Talk compressed-audio recording
- Text typed composer
- temporary Sending/Sent state
- Room/cache
- local response-audio storage
- foreground/background result fetch
- audio playback/routing
- notifications and actions
- alarms/local execution
- offline queues

---

## Simplicity Principle

The user experiences:

```text
One Butler
One day
One Main Screen
One conversation overlay
Three controls: Order / Talk / Text
```

Order/Talk differ from Text at input only. After normalization, they share one Butler brain and one result lifecycle.

---

## Source of Truth

`PROJECT.md` is authoritative for product behavior. Technical documents derive from it. Version history is recorded in `docs/project_source_of_truth/versions/VERSION_UPDATES.md`.
