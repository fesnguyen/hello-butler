# Personal Butler

**Document:** Project Overview  
**Version:** 1.6  
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

## Daily Events, Upcoming Events, and Routines

Butler distinguishes the concrete events in a generated day from future actionable information that may influence later planning.

```text
User Context
├── routine / preference / reference context
└── time-bounded future or currently-active context
        ↓ project when actionable
   Upcoming Events
        ↓ one planning input among others
Daily Planning
├── routines
├── Upcoming Events
├── preferences / constraints
├── existing Daily Events
└── other relevant User Context
        ↓
Daily Plan
└── Daily Events
```

An **Upcoming Event** is a derived, time-aware presentation of actionable User Context. It is **not** a new persistence entity or database table. User Context remains its source of truth. Do not create an `UpcomingEvent` table/model merely to support this concept.

Examples:

- "I have a dentist appointment next Tuesday at 15:00" may appear as one Upcoming Event.
- "I will meet the client every day at 15:00 this week" remains recurring User Context. Today's applicable occurrence may be represented in today's Daily Events, while future applicable occurrences may be shown as Upcoming Events.
- "Diet for a week starting September 22" may appear as one Upcoming Event covering September 22–28 while that period is current or future. It is planning context; it does not imply that one generic "Diet" Daily Event must be created every day.

Upcoming Events are **inputs to planning, not pre-generated future Daily Plans**. An Upcoming Event may influence a Daily Plan without becoming a Daily Event, and Daily Plans are also generated from routines, preferences, constraints, existing events, and other relevant context.

A **daily routine is not an Upcoming Event**. Routines are recurring planning context. When a routine applies to a particular day, planning may materialize the appropriate concrete Daily Events for that day.

Only Upcoming Events whose relevant time range is still active today or lies in the future should be presented. Expired occurrences/ranges are not shown as Upcoming Events.

Upcoming Events must be actionable from the user's perspective like Daily Events: the user may modify, reschedule, skip an occurrence when meaningful, or remove them. These actions mutate the underlying User Context (or an appropriate occurrence/exception representation within the existing context architecture); they must not create a second independent Upcoming Event persistence source. For recurring context, distinguish an occurrence-level change such as "cancel tomorrow's client meeting" from a rule-level change such as "stop these client meetings."

On the Main Screen, Upcoming Events appear **below the Daily Plan**, separated from Daily Events by a clear, eye-catching visual divider/section boundary. They must not be visually mixed into today's Daily Event list.


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

## Source of Truth

`PROJECT.md` is authoritative for product behavior. Technical documents derive from it. Version history is recorded in `docs/project_source_of_truth/versions/VERSION_UPDATES.md`.
