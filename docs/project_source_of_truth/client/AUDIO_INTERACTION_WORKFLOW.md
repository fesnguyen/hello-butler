# Client Recorded Audio Interaction Workflow

**Status:** Proposed for review  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `../AUDIO_INTERACTION.md`

---

# Purpose

This document defines the Android behavior for Hello Butler's recorded push-to-talk interaction model.

The client must make voice capture available immediately, record locally while the user holds the control, upload after release, present durable Butler responses as text + audio, and continue to work correctly when the app is backgrounded or offline.

This is not a live voice-chat client.

---

# Primary Voice Interaction

```text
user presses and holds
        ↓
client records locally
        ↓
user speaks
        ↓
user releases
        ↓
client finalizes recording
        ↓
upload immediately or queue
        ↓
backend processes
        ↓
text + audio result
        ↓
foreground conversation or background notification
```

The client must not depend on Android `SpeechRecognizer` for normal Butler Order/Talk interpretation.

The client sends the actual recording to the backend.

---

# Interaction Controls

The primary actions remain:

```text
Order
Talk
Text
```

All three may begin with the same hold-to-record behavior.

The selected action changes the expected backend behavior, not the audio-capture mechanism.

## Order

Use when the user wants Butler to handle the request and may leave immediately.

Expected client behavior:

```text
hold → record → release → submit/queue → user may leave
```

The UI should acknowledge that the request was captured/sent without forcing the user to wait for the final result.

## Talk

Use when the user is present and expects a response quickly.

Expected client behavior:

```text
hold → record → release → submit → wait for result when convenient
```

If the app leaves the foreground before the result arrives, the result becomes a normal durable/background response rather than being dropped.

## Text

Use when the user wants editable text before Butler acts on it.

```text
hold
  ↓
record
  ↓
release
  ↓
backend prepares text using audio + context/preferences
  ↓
show editable draft
  ↓
user edits/reviews
  ↓
explicit send
```

The client must not execute or submit the prepared draft automatically.

---

# Instant App Startup

The app must open into a usable shell without waiting for remote work.

The key requirement is:

> After normal first-run permissions have already been granted, the hold-to-talk control becomes usable as soon as the main shell is rendered.

The critical startup path is deliberately small:

```text
process/activity start
      ↓
render main shell from local state
      ↓
initialize recording capability
      ↓
enable hold-to-talk
```

The following work must not block that path unless technically unavoidable:

```text
backend health checks
OpenAI/provider initialization
full conversation fetch
today-plan refresh
pending-response fetch
Room/server reconciliation
nonessential image/content loading
analytics initialization
other remote hydration
```

The screen may progressively fill with cached/synchronized data after the user can already speak.

---

# Startup Lanes

Conceptually the Android startup should use two lanes:

```text
                         APP LAUNCH
                             │
                  ┌──────────┴──────────┐
                  │                     │
             CRITICAL PATH        DEFERRED PATH
                  │                     │
            render shell            read/sync Room
            init recorder           fetch today's plan
            enable controls         fetch history
                  │                 fetch pending results
                  │                 refresh remote state
                  ▼                     │
              USER CAN TALK             ▼
                                  UI progressively hydrates
```

Local database reads that are fast enough to support first paint are allowed, but remote synchronization must not gate voice capture.

---

# Microphone Permission

First-run Android microphone permission is an unavoidable prerequisite.

After permission has been granted, subsequent launches should not place unrelated setup in front of recording.

If permission is missing/revoked:

- render the main shell normally
- explain why microphone access is required when the user tries to record
- keep typed/manual interactions available where possible

Do not make microphone permission failure block unrelated daily-plan features.

---

# Recording Component

The client should introduce a recording abstraction rather than embedding audio capture into composables or ViewModels.

Conceptually:

```text
RecordedAudioController
├── startRecording()
├── stopRecording()
├── cancelRecording()
├── recording state
├── local output reference
├── duration / size validation
└── audio format metadata
```

The exact Android API/library may be chosen during implementation.

Requirements:

- mono voice-oriented recording
- compressed output
- low startup latency
- deterministic stop on button release
- cancellation support
- duration/size limits
- safe cleanup of abandoned files
- resilient local file handling across temporary process/background events where practical

Prefer Opus where implementation/provider compatibility is good, but keep the transport contract codec-flexible.

---

# Submission State

After release, the recording moves through explicit states so the UI can remain understandable:

```text
recording
   ↓
ready_to_send
   ↓
queued | uploading
   ↓
processing
   ↓
completed | clarification | failed
```

The user should receive immediate local acknowledgement that the recording was captured even if network submission takes longer.

For Order, the app may collapse the interaction after capture and continue processing in the background.

---

# Offline and Weak-Network Capture

The user must be able to capture a request when connectivity is unavailable.

```text
hold
 ↓
record locally
 ↓
release
 ↓
no network
 ↓
store queued request safely
 ↓
show queued state
 ↓
network/auth returns
 ↓
upload
```

Queued audio requests require:

- unique client request id
- interaction mode
- local audio reference
- created timestamp
- retry state
- authenticated user relationship

The queue must avoid duplicate execution when retrying the same Order.

After successful confirmed upload, local raw audio should be cleaned up according to the queue lifecycle.

---

# Foreground Response

When the app is open and a response arrives:

```text
response
  ↓
store/sync locally
  ↓
append to conversation presentation
  ↓
show canonical text
  ↓
show audio playback action
```

The conversation UI should not depend on the audio asset to display the result. Text must remain usable if audio download/generation failed.

If audio is available, the client may cache it for immediate replay.

---

# Background / Idle Response

If the app is not foregrounded, the server may send a push signal for a new durable Butler response.

The notification should use compact data, typically including a response identifier and safe text preview.

The client then fetches/caches the full authorized response/audio when needed and when Android background rules permit.

Target notification actions:

```text
Listen privately
Speak aloud
Read / Open
```

These are product behaviors; exact Android action availability may vary by OS/device state.

## Listen Privately

The goal is the experience of bringing the phone to the ear and hearing Butler privately.

This is audio playback, not a real phone call.

The client should use an earpiece/private-oriented audio route where supported and should respect audio focus, connected Bluetooth/headset state, and Android routing behavior.

## Speak Aloud

Play the same response audio through the normal public/loudspeaker route while respecting audio focus and user settings.

## Read / Open

Display the canonical text in the expanded notification where practical, or open the app to the corresponding response/conversation.

---

# Notification Privacy

Notification previews must follow user privacy preferences.

The system should be able to support configurations such as:

```text
show full preview
show short preview
show generic "Butler has a response"
```

Audio should never be embedded directly into the push payload.

---

# Response Playback

Conversational Butler responses use server-generated/provider-generated audio when available.

Playback UI should expose a reusable control rather than tying audio to one transient overlay.

Conceptually:

```text
Butler message
────────────────────────
Got it. I moved your meeting
from 3:00 PM to 4:00 PM.

[ ▶ Listen ]
```

The audio corresponds to the canonical text for that response.

The user may replay it later while the response remains available.

---

# Audio Routing

Playback behavior should be separated from content retrieval.

Conceptually:

```text
ButlerAudioPlayer
├── playPublic(responseAudio)
├── playPrivate(responseAudio)
├── pause/stop
├── audio focus
├── Bluetooth/headset awareness
└── playback progress/state
```

Do not model private playback as an actual telephone/VoIP call unless a future product requirement explicitly introduces calling behavior.

---

# Local TTS Role

Android local Text-to-Speech should no longer be the primary conversational response path when server-generated Butler audio exists.

Local TTS remains valuable for:

- offline fallback
- prepared Daily Event speech
- already-synchronized Morning Brief / Good Night content when audio asset is unavailable
- local reminders where configured
- emergency degradation when response audio cannot be fetched

The client should not delete existing local TTS support merely because conversational response audio moves to the server.

---

# Text Butler Draft Experience

Text Butler should feel like speech-assisted composition rather than command execution.

Example:

```text
User holds Text and says:
"Tell John là tôi sẽ tới trễ khoảng mười lăm phút"

backend returns editable draft:
"Tell John I'll be about 15 minutes late."

client shows editor
     ↓
user changes wording if desired
     ↓
explicit Send
```

The draft should not appear as an already-executed Butler command.

If preparation fails, preserve the recording long enough for retry or allow the user to record again.

---

# Conversation Storage on Client

Room should continue to cache text-oriented conversation data and response metadata.

Suggested local representation needs to support:

```text
response id
role
canonical text
timestamp
audio availability/reference/cache state
delivery/read state
```

Raw user recordings belong to the pending-upload queue, not long-term conversation storage.

Cached response audio belongs to a bounded media cache and may be evicted/re-fetched according to retention and authorization rules.

---

# Pending Results on App Resume

Opening/resuming the app should synchronize missing durable responses, but that work must remain separate from enabling recording.

```text
app resume
  ├── enable interaction immediately
  └── sync pending responses in parallel
```

If a push was missed, the normal sync path still recovers the response.

Push is notification, not storage.

---

# UI State Priorities

The interaction controls should communicate only states the user needs to understand:

```text
ready
recording
captured/sending
queued offline
processing
response available
failed/retry
```

Avoid exposing provider terminology such as "transcribing", "multimodal inference", "TTS generation", or model names in normal UI.

The product should continue to feel like one Butler.

---

# Cancellation

Before release, cancelling the hold/gesture should discard the local recording unless product behavior explicitly offers recovery.

After a request has been accepted for processing:

- cancellation is best-effort
- do not pretend a domain action was cancelled if it has already committed
- UI must reflect authoritative backend state

This matters most for Order requests that may continue after the app is backgrounded.

---

# Failure Handling

## Upload Failure

Keep the request queued and retry safely.

## AI Processing Failure

Show a retriable Butler error/result without losing the user's captured request prematurely.

## Domain Mutation Succeeds but Audio Generation Fails

Show the canonical text confirmation. Audio is optional degradation.

## Push Failure

Recover the response through normal synchronization next time the app opens.

## Audio Download Failure

Keep text available and allow retry/fallback to local TTS where appropriate.

---

# Client Architecture Direction

The client should evolve away from:

```text
SpeechRecognizer
   ↓
recognized text
   ↓
Butler API
   ↓
text
   ↓
Android TextToSpeech
```

for normal conversational voice turns.

The target direction is:

```text
RecordedAudioController
   ↓
queued/uploaded recording
   ↓
Butler API
   ↓
durable response metadata
   ├── canonical text
   └── response audio
          ↓
ButlerAudioPlayer
```

Existing local TTS remains as an offline/fallback execution service.

---

# Explicit Non-Goals

Do not add these to the client for this feature:

- permanent live microphone connection
- ChatGPT-style realtime duplex voice mode
- WebRTC session management
- direct permanent OpenAI credentials
- client-side AI planning
- audio blobs stored indefinitely in Room
- startup that waits for full server synchronization before enabling voice

---

# Acceptance Behavior for Future Implementation

A future implementation should be considered aligned with this design when all of the following are true:

1. User launches the app and can start recording before remote synchronization finishes.
2. Holding Order/Talk records locally; release sends or queues the audio.
3. Backend, not Android STT, performs normal conversational understanding.
4. Text mode returns an editable server-prepared draft before any command execution.
5. Order can finish after the user leaves the app.
6. A completed response appears as canonical text with replayable audio when available.
7. A background response can notify the user and be listened to or read later.
8. Private and public playback are separate presentation choices.
9. Offline capture queues safely without duplicate execution.
10. Existing offline Daily Event execution remains functional.
