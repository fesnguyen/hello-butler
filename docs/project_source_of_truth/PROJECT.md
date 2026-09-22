# Personal Butler

**Document:** Project Overview  
**Version:** 1.7  
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

### Daily Events, Upcoming Events, and routines

An **Upcoming Event** is actionable, time-bounded information projected from User Context for a period that is active today or still in the future. It is not a new persistence entity: do **not** create an `UpcomingEvent` table/model. User Context remains the source of truth.

```text
User Context
├── routines / preferences / reference information
└── actionable time-bounded information
        ↓
   Upcoming Events
        ↓ one planning input among others
Daily Planning
├── routines
├── Upcoming Events
├── preferences / constraints
├── existing events
└── other relevant context
        ↓
Daily Plan → Daily Events
```

Upcoming Events are a source for Daily Plan generation, **not pre-generated future Daily Plans**. Not every Upcoming Event must become a Daily Event, and Daily Plans are not generated only from Upcoming Events.

A daily routine is **not** an Upcoming Event. Routines remain recurring planning context; when a routine applies to a day, planning may materialize appropriate Daily Events for that day.

Examples:
- "Dentist next Tuesday at 15:00" can project one Upcoming Event.
- "Meet the client every day at 15:00 this week" remains recurring User Context. Today's applicable occurrence may be represented in today's Daily Events while future applicable occurrences are shown as Upcoming Events.
- "Diet for a week starting September 22" can project one Upcoming Event covering September 22–28. It influences planning while active/relevant but does not require a generic "Diet" Daily Event every day.

Only Upcoming Events whose applicable period is active today or in the future are displayed. Expired items are not shown.

Upcoming Events are user-actionable like Daily Events: they can be modified, rescheduled, skipped when meaningful, or removed. Those operations update the underlying User Context or the appropriate occurrence/exception semantics; they must not create a second authoritative Upcoming Event record. For recurring context, distinguish an occurrence-level change such as "cancel tomorrow's client meeting" from a rule-level change such as "stop these client meetings."

On the Main Screen, Upcoming Events are displayed **below the Daily Plan**, separated from Daily Events by a clear, eye-catching divider/section boundary.


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

Order and Talk use recorded audio as their normal input transport. Text uses direct typed text and does not record audio or use local STT.

---

## Correct AI Input/Output Contract

For Order/Talk, the original recorded audio must remain part of the Butler AI input. The backend combines that audio with textual context before calling the audio-capable multimodal model.

```text
Order / Talk audio
        +
textual Butler context
        ├── recent conversation
        ├── User Context / preferences
        ├── relevant Daily Plan / Events
        ├── now / timezone
        ├── interaction mode
        └── Butler instructions
        ↓
audio-capable multimodal Butler model
        ↓
canonical result
        ├── user transcript / understood utterance text
        ├── structured intent / action decision when needed
        ├── Butler response text
        └── Butler response audio
```

The normal architecture must **not** reduce Order/Talk to a standalone STT result and then use that transcript as the only semantic input to a text-only Butler model.

A transcript is still required for conversation history and display, but it is an output/artifact of the multimodal interaction, not the sole reasoning boundary.

The normal backend path must also not be modeled as `STT → text-only reasoning → separate TTS` when an audio-capable multimodal model/API can accept the original audio plus text context and produce the Butler text + audio result.

---

## Text Interaction

Text remains direct composition:

```text
tap Text
   ↓
type/edit text
   ↓
Send
   ↓
backend combines typed text + Butler context
   ↓
shared Butler reasoning/application behavior
   ↓
canonical Butler response text + audio
```

Text does not record audio and does not use STT.

Order/Talk and Text may have different input payloads, but they still share one Butler product behavior, one asynchronous request lifecycle, one domain/action layer, and one canonical result model.

---

## One Interaction, One Conversation Result

A completed Butler interaction becomes:

```text
one user message
+
one Butler message
```

For audio input, the user message is the model-produced transcript/understood utterance text. For text input, it is the exact submitted text.

The Butler message contains canonical response text and associated response audio.

There is no separate in-app response and notification response. A notification is only another presentation surface for the same Butler message.

---

## Asynchronous Request Lifecycle

The async transport architecture remains unchanged:

```text
POST audio OR POST text
      ↓
backend creates durable request
      ↓
202 + request_id
      ↓
backend continues asynchronously
      ↓
load Butler context
      ↓
AI processing
      ├── audio request: original audio + text context
      └── text request: typed text + text context
      ↓
apply validated domain actions when needed
      ↓
final canonical response text + audio
      ↓
persist result text / metadata
      ↓
save response audio temporarily on backend
      ↓
FCM completed(request_id)
      ↓
client fetches canonical text/result metadata
      ↓
client immediately downloads response audio
      ↓
Room + local audio cache
```

FCM is a wake-up/completion signal, not the canonical response payload and not an audio transport.

---

## Response Delivery Rule

The backend must persist the completed text result and make the response audio available for authenticated download.

Conceptually:

```text
AI returns text + audio
      ↓
backend commits canonical text result
backend stores temporary audio asset
      ↓
client is notified / fetches result
      ↓
client receives response text + audio-ready reference
      ↓
client downloads audio immediately
```

The text result must remain usable even if response-audio download is delayed. The audio asset is retention-limited on the backend and long-lived primarily in the client's local cache.

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
          Room + immediate audio cache
```

Foreground/background only changes how Android executes work. It must not change the product result.

---

## Recorded Voice UI

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
Sending...
      ↓
Sent • <time>
      ↓
completed result
      ↓
replace temporary user content with transcript
```

Conversation history remains visible while recording. The user has no normal need to replay their own recorded request.

---

## Response Audio

A Butler message exposes compact playback actions:

```text
speaker icon → play aloud
phone icon   → private/call-style listening
```

The client starts fetching/caching response audio immediately after completion. If playback is selected before download finishes, wait for the in-progress download and play when ready.

---

## Notification Behavior

Ordinary Butler-response notifications contain the same canonical response text and expose:

```text
speaker icon
phone / private-listen icon
Open in App
```

`Open in App` opens the single Main Screen with the Butler conversation overlay visible; there is no separate conversation screen.

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
- stores uploaded Order/Talk audio only as temporary processing input
- stores completed Butler response audio long enough for client retrieval/recovery
- removes old input and response audio regularly
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
    │     └── make Text composer available immediately
    │
    └── background path
          ├── load/sync Daily Plan
          ├── load conversation history
          ├── fetch pending results
          └── reconcile other data
```

---

## Backend and Client Boundary

### Backend owns

- Butler reasoning
- multimodal understanding of uploaded Order/Talk audio together with textual context
- transcript/understood utterance text for audio messages
- User Context / conversation / Daily Plan context assembly
- domain action orchestration and validation
- conversation text history
- canonical response text + response audio handling
- temporary response-audio storage and retention
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

The backend should preserve the richest user input available. For Order/Talk, that means the original audio remains available to the AI reasoning path instead of being discarded after a separate transcription step.

---

## User Settings, Credits, TTS, and Preferences

The client menu/surface is named **User Settings** (replacing the previous Profile name). Backend user/profile persistence remains the authoritative account/configuration model; do not create a separate generic settings/configuration table. The initial editable setting is the preferred TTS method:

```text
Profile
├── Credits            read-only on the client
└── TTS Method         editable
    ├── OPEN_SOURCE
    └── OPENAI
```

Credits are Hello Butler product credits, not raw OpenAI input/output token counts. Paid Butler operations such as AI interaction and planning require available credits and consume credits according to backend policy. The backend is authoritative for credit checks and deductions; the client only displays the balance.

Response text remains canonical. TTS is a separate optional speech-generation step. The backend keeps the existing OpenAI TTS path and adds an open-source TTS path, initially Kokoro.

```text
canonical Butler response text
        ↓
read User/Profile.tts_method
        ├── OPEN_SOURCE → Kokoro/open-source TTS → no additional credits
        └── OPENAI
              ├── credits > 0 → OpenAI TTS → additional credit cost
              └── credits <= 0 → Kokoro/open-source TTS
```

Running out of credits must **not** overwrite the stored `OPENAI` preference. The fallback is runtime-only so OpenAI TTS becomes effective again if the user later has credits. Open-source TTS is only a speech fallback; it does not replace the paid AI reasoning/planning model. If paid Butler reasoning has insufficient credits, handle that explicitly rather than treating TTS fallback as a reasoning fallback.

TTS failure must not invalidate an otherwise successful Butler interaction. The canonical response text remains usable even when response audio cannot be generated.

The Android client exposes these controls through **User Settings**: show the current credit balance as read-only and allow the user to edit the TTS method.

User Settings also exposes the user's saved Butler preferences derived from the existing preference/User Context source of truth. Display preferences as a compact list with **one preference per line**, truncating long content with an ellipsis. Tapping a preference opens a detail popup/dialog showing the full content and a delete action. The user can delete saved preferences individually. Deletion must remove/update the authoritative backend preference/User Context rather than maintaining a client-only copy. Require normal confirmation for destructive deletion and refresh/reconcile the list after success.

This preference list is for user-manageable saved preferences (similar in spirit to reviewing remembered preferences), not every User Context record. Do not expose unrelated routines, temporary planning context, upcoming-event context, or internal metadata merely because they share persistence infrastructure.

---

## Source of Truth

`PROJECT.md` is authoritative for product behavior. Technical documents derive from it. Version history is recorded in `docs/project_source_of_truth/versions/VERSION_UPDATES.md`.
