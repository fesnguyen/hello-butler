# Personal Butler

**Document:** Project Overview  
**Version:** 1.9
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

Daily Events represent the user's day. User Context stores durable, recurring, temporary, one-time, preference, and note-like information that Butler can use or present as appropriate.

### Daily Events, Upcoming Events, and routines

An **Upcoming Event** is actionable, time-bounded information projected from User Context for a period active today or in the future. It is not a persistence entity: do **not** create an `UpcomingEvent` table/model.

Upcoming Events are one input to Daily Planning, alongside routines, preferences, constraints, existing events, and other relevant context. Not every Upcoming Event becomes a Daily Event, and Daily Plans are not generated only from Upcoming Events.

A daily routine is not an Upcoming Event. Only active/future time-bounded context is displayed as Upcoming. Mutating an Upcoming Event updates its underlying User Context or occurrence/exception semantics rather than creating another authoritative record.

On Main Screen, Upcoming Events appear below Daily Plan behind a clear section boundary.

---

## Three Primary Butler Controls

The client exposes three persistent controls:

```text
Order → recorded audio; handle this for me
Talk  → recorded audio; I am actively talking with Butler
Text  → typed/editable text
```

They are three input modes for one Butler, not separate assistants or reasoning systems. Order/Talk preserve original audio through the multimodal understanding call; Text uses submitted text directly.

---

## Butler Interaction Contract

The backend combines the user's input with relevant Butler context before the understanding call:

```text
input (original audio OR typed text)
+
recent conversation
User Context / preferences
relevant Daily Plan / Events
now / timezone
interaction mode
Butler instructions
      ↓
multimodal Butler model
      ↓
user_message_text
supported proposed mutation
canonical response_text
```

Application/domain code validates and persists state changes. The AI does not write state directly.

For audio, `user_message_text` is the model-produced transcript/understood utterance. For Text, it is the submitted text. One completed interaction becomes one user message plus one Butler message.

TTS is separate from understanding. Canonical response text is persisted and delivered before optional speech finishes.

---

## Asynchronous Request and Speech Lifecycle

```text
POST audio/text → 202 + request_id
      ↓
understand + apply validated actions
      ↓
persist canonical conversation text
      ↓
request completed + notify client
      ↓
client displays text immediately
      ↓
shared backend TTS prepares speech
      ↓
audio: pending → processing → ready | unavailable
```

FCM is a wake-up signal, not canonical content or audio transport. TTS failure never invalidates successful text/actions.

All Butler-generated speech uses the shared TTS service, including conversation responses, Morning Brief, and Good Night Summary. The stored TTS preference selects Open Source/Kokoro or OpenAI; insufficient OpenAI TTS credits cause a runtime Kokoro fallback without changing the stored preference.

---

## Recorded Voice UI

Order and Talk use press-and-hold recording:

```text
press and hold → record → release → upload → Sending… → Sent • <time> → reconcile transcript/result
```

Conversation remains visible while recording. Text uses direct composition and no STT.

---

## Response Audio Controls

Each Butler message provides two compact but clearly tappable **horizontal playback controls**:

```text
Listen Aloud   → normal speaker route
Phone Listen   → private/call-style audio route
```

The phone action is only a listening route, not a real network call or realtime Butler session. Both actions use the same Butler response audio and each exposes the same user-visible lifecycle:

```text
Loading → Ready → Speaking
                    ↓
                 [Stop ■]
```

- **Loading:** audio is being prepared, fetched, or cached; prevent duplicate playback requests.
- **Ready:** show the normal playback action.
- **Speaking:** replace the playback icon/action with a square Stop action that immediately stops playback.

The controls must have a comfortable touch target and be visibly wider than bare icons without turning the message into a large action panel. Only one playback route owns the audio at a time; starting the other route stops/switches the current playback rather than overlapping audio.

Morning Brief and Good Night Summary may auto-start Listen Aloud when due when daily briefing speech is enabled. Reminder and ordinary response speech are separately opt-in. Manual playback remains available regardless of these automatic-speech preferences. Playback and delayed continuations expose Stop.

All Butler-managed openings, warm-ups, previews, and generated speech use a device-local Butler volume (0–100%, default 70%). This is player gain, separate from Android system volume; changing it must not affect other apps, alarms, ringtones, or notifications. Android's media volume still limits audible output.

Audio sequences are configuration-driven. Morning Brief plays a long opening and random morning warm-up, then waits five minutes after the warm-up finishes before a short opening and the prepared speech. Good Night plays a long opening, random evening warm-up, and prepared speech without delay. Reminders use a short opening then reminder speech; responses use their existing speech. Details are in `client/AUDIO_WORKFLOWS.md`.

---

## User Settings

The client destination is **User Settings**. It contains account information, read-only credits, editable TTS method, Sound & Voice, and user-manageable saved context. Sound & Voice saves Butler volume immediately and exposes Speak reminders aloud, Speak Butler responses aloud, and Speak daily briefings aloud. The live volume sample is prerecorded and never invokes TTS.

Backend profile persistence remains authoritative for account/configuration data. User Context remains authoritative for Butler knowledge about the user's life. Do not create a generic settings table or a separate notes/preferences table for this scope.

### Notes and Preferences

User Settings presents a single manageable saved-context area containing **Notes and Preferences**.

A saved item requires only its text/description. No title is required in the current design. It also carries the semantic distinction of whether it is a preference:

```text
Saved item
├── description      required
└── is preference    yes / no
```

Use the existing User Context/preference persistence and domain boundary. Do **not** create a new `Note` table/entity solely for this feature. If the existing persistence needs a type/flag to distinguish ordinary notes from preferences, extend/reuse that model rather than duplicating storage.

Semantics:

- **Preference:** information about the user's likes, dislikes, habits, or choices that Butler may use for personalization/planning when relevant.
- **Note:** user-saved information that should remain retrievable/manageable but must not automatically be treated as a personalization preference.

Example: conversationally telling Butler, "I love going to the beach when I have a day off," may create/update a preference. That same authoritative item must appear in User Settings after synchronization.

Users can add a note directly from User Settings, edit/delete saved items, and switch whether an item is a preference. Mutations update the same backend source used by Butler; there is no client-only preference/note store.

Only user-manageable notes/preferences belong in this list. Do not expose routines, temporary planning context, Upcoming Events, or internal metadata merely because they share User Context persistence.

### Preference synchronization

Preferences remembered through Butler conversation and preferences/notes changed directly in User Settings must converge on the same backend state:

```text
Butler conversation ─┐
                     ├→ authoritative User Context → sync/API → Room/cache → User Settings
User Settings ───────┘
```

Synchronization requires no manual Refresh. After a successful conversational or direct mutation, the client reconciles the saved-context list using the same repository/cache pattern used elsewhere in the app. Editing/deleting an item from User Settings changes what Butler subsequently sees as authoritative context.

---

## Credits and TTS Method

Credits are Hello Butler product credits, not raw provider token counts. Backend policy owns checks/deductions; the client only displays balance.

```text
canonical response text
        ↓
User/Profile.tts_method
        ├── OPEN_SOURCE → Kokoro → no additional TTS credits
        └── OPENAI
              ├── credits > 0 → OpenAI TTS
              └── credits <= 0 → Kokoro runtime fallback
```

Running out of credits does not overwrite the stored OpenAI selection. TTS fallback is speech-only and never substitutes for paid reasoning/planning requirements.

---

## Startup, Sync, and Ownership

The app becomes usable immediately: render Main Screen and enable Order/Talk/Text before background synchronization completes. Room is the client's immediate working state; authenticated backend reconciliation is authoritative.

Backend owns Butler reasoning, authoritative persistence, planning, context mutations, conversation text, TTS generation, temporary response audio, and completion push publication.

Client owns immediate UI, recording/composition, Room/cache, synchronization workers, local audio cache/playback/routing, notifications, alarms/local execution, and offline queues.

Conversation text is durable cross-device history. Backend audio is retention-limited; locally cached audio supports later playback.

---

## Simplicity Principle

The user experiences:

```text
One Butler
One day
One Main Screen
One conversation overlay
Three controls: Order / Talk / Text
One authoritative saved-context system
```

Preserve rich input, keep canonical text independent of optional speech, and avoid duplicate persistence models when an existing authoritative model already owns the concept.

---

## Source of Truth

`PROJECT.md` is authoritative for product behavior. Technical documents derive from it in this order: `ENGINEERING.md` (shared engineering rules), subsystem entry points (`backend/PROJECT_BACKEND.md`, `client/PROJECT_CLIENT.md`, `web/PROJECT_WEB.md`), then their linked feature documents. A child may add implementation detail but must not silently contradict a parent. Read only the relevant subsystem and feature documents for a change. Version history is recorded in `docs/project_source_of_truth/versions/VERSION_UPDATES.md`.
