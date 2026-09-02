# Client Workflow

**Version:** 1.1  
**Status:** Initial  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `CLIENT_ARCHITECTURE.md`

---

# Purpose

This document defines the Android client's user-facing workflows and UI behavior.

It focuses on:

- Main Screen behavior
- Daily Event interaction
- Event configuration
- Butler Order / Talk / Text interaction
- Butler conversation overlay
- Butler late-response presentation
- Morning Brief and Good Night Summary presentation
- speech and response choices

Technical implementation details that do not directly affect UI behavior belong in `CLIENT_ARCHITECTURE.md` or implementation code.

---

# Main Screen

The Main Screen is the primary client surface.

```text
MainScreen
│
├── DailyContent
│   ├── Today
│   ├── DailyEventList
│   └── Upcoming information
│
├── ButlerConversationOverlay
│
├── ButlerResponseOverlay
│
└── ButlerControlBar
    ├── Order
    ├── Talk
    └── Text
```

The user should normally stay on the Main Screen while managing their day or interacting with Butler.

Butler interaction does not navigate to a separate chat screen.

---

# Main Screen Layout

Conceptually:

```text
┌────────────────────────────────┐
│ Today                          │
│                                │
│ Morning Brief              ⋮   │
│ Breakfast                  ⋮   │
│ Work                       ⋮   │
│ Meeting                    ⋮   │
│ Exercise                   ⋮   │
│                                │
│ Upcoming                       │
│ Tomorrow: Appointment          │
│                                │
├────────────────────────────────┤
│     Order     Talk     Text     │
└────────────────────────────────┘
```

Each event item has a configuration button at its end.

The Butler controls remain available at the bottom.

---

# Daily Event Item

Each Daily Event is shown as a compact row/card.

Conceptually:

```text
┌────────────────────────────────┐
│ 15:00   Project Review      ⋮   │
│         Meeting                │
└────────────────────────────────┘
```

The event item may show:

- time
- title
- short description or type
- current state
- visual indication for completed/past events
- configuration button

The configuration button should remain easy to reach without requiring Butler interaction.

---

# Event Configuration Button

A button at the end of every event opens the Event Detail popup.

Example:

```text
Meeting                          ⋮
                                 ↑
                          configure event
```

The button is for deterministic event management.

It should not call Butler or AI.

---

# Event Detail Popup

Selecting the event configuration button opens a popup / overlay for that event.

```text
┌────────────────────────────────┐
│ Project Review                 │
│                                │
│ Time          15:00            │
│ End           16:00            │
│ Reminder      15 min before    │
│ Speak aloud   Off              │
│ Status        Planned          │
│                                │
│ [Complete] [Delay] [Skip]      │
│                                │
│ [Save]                 [Close] │
└────────────────────────────────┘
```

The exact fields shown depend on what the event supports.

Possible controls include:

- title
- description
- start time
- end time / duration
- reminder timing
- speak-aloud behavior
- complete
- skip
- delay
- move
- cancel
- delete

The popup should stay focused on the selected event rather than exposing unrelated application settings.

---

# Event Configuration Workflow

```text
User taps event config button
        ↓
Event Detail popup opens
        ↓
User changes fields / state
        ↓
Save
        ↓
Event list updates immediately
        ↓
Popup closes
```

Direct event configuration is local-first and should feel immediate.

The user should not need to wait for Butler to edit an event that is already visible and directly configurable.

---

# Butler Control Bar

Three Butler buttons remain fixed at the bottom:

```text
┌───────────────────────────────┐
│     Order     Talk     Text    │
└───────────────────────────────┘
```

All three use the same Butler conversation overlay.

They differ in interaction behavior.

---

# Butler Conversation Overlay

When the user presses any Butler button, a conversation popup appears directly above the controls.

```text
┌────────────────────────────────┐
│                                │
│       Daily Event List         │
│       remains visible          │
│                                │
│ ┌────────────────────────────┐ │
│ │ Butler Conversation       │ │
│ │                           │ │
│ │ You: Shift meeting...    │ │
│ │ Butler: Done.            │ │
│ │                           │ │
│ │ STT / composer           │ │
│ └────────────────────────────┘ │
├────────────────────────────────┤
│     Order     Talk     Text     │
└────────────────────────────────┘
```

Initial behavior:

```text
ButlerConversationOverlay
├── anchored above ButlerControlBar
├── appears when press-and-hold begins
├── begins compact
├── expands as content grows
├── maximum height ≈ 70% screen [DRAFT]
├── scrolls internally after maximum height
└── leaves the Daily Event list visible
```

The overlay exists on top of the current day rather than becoming a separate chat page.

---

# Shared Press-and-Hold Flow

All three buttons begin the same way:

```text
Press and hold
      ↓
Conversation overlay appears
      ↓
Start speech recognition
      ↓
User speaks
      ↓
Live STT shown
      ↓
Release
```

The user can watch recognition while still seeing the Daily Event list.

Behavior after release depends on the chosen button.

---

# Order Workflow

Order means the user does not need to stay in an active conversation.

```text
Press and hold Order
        ↓
Speak
        ↓
Release
        ↓
Send immediately
        ↓
Show user message
        ↓
Butler processes
```

If the result comes back immediately, it appears in the conversation overlay.

After a completed result:

```text
Show result
    ↓
Keep visible briefly
    ↓
Auto-dismiss
```

Initial hold duration:

```text
≈ 5 seconds [DRAFT]
```

If the result arrives later, use the Butler Response Overlay described below.

---

# Talk Workflow

Talk means the user expects to stay actively engaged.

```text
Press and hold Talk
        ↓
Speak
        ↓
Release
        ↓
Send immediately
        ↓
Show user message
        ↓
Butler processes
        ↓
Show response
        ↓
Keep conversation overlay open
```

The user can continue the conversation.

Talk may answer a question, change an event, or ask for clarification.

---

# Text Workflow

Text is a precise input-preparation path.

```text
Press and hold Text
        ↓
Speak
        ↓
Release
        ↓
Transcript becomes editable
        ↓
User reviews / edits
        ↓
Choose:
   ├── Send as Order
   └── Send as Talk
```

Conceptually:

```text
┌──────────────────────────────┐
│ Shift my meeting to 4 PM_    │
│                              │
│ [ Send as Order ] [ Talk ]   │
└──────────────────────────────┘
```

Text does not create a third Butler semantic mode.

It produces an exact message, then sends it with either Order or Talk behavior.

---

# Conversation Continuity

The Butler overlay shows the active conversation rather than only the latest message.

```text
You:
Move my meeting to 4.

Butler:
Which meeting?

You:
Project Review.

Butler:
Done. Project Review is now at 4 PM.
```

Clarifications continue in the same overlay.

---

# Butler Late Response

A Butler response may arrive after the original conversation popup has already closed, especially for Order.

Late responses use a dedicated Butler Response Overlay.

Conceptually:

```text
┌────────────────────────────────┐
│ Butler                         │
│                                │
│ Your Project Review meeting    │
│ has been moved to 4:00 PM.     │
│                                │
│ [Call] [Speak] [OK, I see]     │
└────────────────────────────────┘
```

This popup appears over the Main Screen so the user can still see their current day.

---

# Butler Response Choices

For Butler responses, the user controls how they consume the response.

Default presentation:

```text
response arrives
      ↓
show response text overlay
```

The user can then choose:

```text
Call
Speak
OK, I see
```

These correspond to:

### Call

Present the Butler response like receiving a phone call.

The user explicitly enters a more immersive listening mode.

This is useful when the user wants to listen without reading and without having the response spoken publicly.

Exact call-screen visual design can be refined later.

### Speak

Read the response aloud immediately through TTS.

This is appropriate when the user is comfortable having Butler speak through the device speaker.

### OK, I see

Acknowledge the text response and close the overlay without speech.

---

# Response Preference

The user's preferred response presentation may be configurable.

Possible preference:

```text
Butler response default
├── Text overlay
├── Speak aloud
└── Call-style receive
```

Initial product default for ordinary Butler responses:

```text
Text overlay
```

This avoids unexpectedly speaking private responses aloud.

The user can still choose `Call` or `Speak` from the response overlay.

A future user preference may change the default behavior.

---

# Call-Style Response

Call-style response is a listening presentation mode.

Conceptually:

```text
Butler Response
      ↓
User chooses Call
      ↓
Call-style overlay / screen
      ↓
Butler speaks response
      ↓
User listens
      ↓
End / close
```

The intent is similar to receiving a short phone call from Butler.

This is distinct from automatic speaker playback.

The exact audio route, screen design, and interaction controls can be refined during UI design.

---

# Speak-Aloud Response

If the user chooses Speak:

```text
Response overlay
      ↓
Tap Speak
      ↓
TTS starts
      ↓
Response remains visible
      ↓
User may stop / close when finished
```

The response text remains visible so listening and reading can happen together.

---

# Morning Brief

Morning Brief is different from ordinary Butler responses.

Default:

```text
Morning Brief
      ↓
Speak aloud automatically
```

The content can also remain available visually in its Daily Event / associated presentation.

This behavior is configurable by the user.

---

# Good Night Summary

Good Night Summary also defaults to spoken presentation.

```text
Good Night Summary
      ↓
Speak aloud automatically
```

The user may configure this behavior.

---

# Default Speech Behavior

Initial defaults:

```text
Morning Brief
→ Speak aloud

Good Night Summary
→ Speak aloud

Ordinary Butler response
→ Show text overlay
```

For an ordinary Butler response, the user may explicitly choose:

```text
Call
Speak
OK, I see
```

This keeps Butler from unexpectedly speaking normal responses in public while allowing proactive daily rituals to remain voice-first.

---

# Butler Response Overlay Behavior

The response overlay should be compact and non-destructive.

```text
ButlerResponseOverlay
├── appears over Main Screen
├── displays response text
├── allows reading without leaving current context
├── offers Call
├── offers Speak
├── offers OK / close
└── does not require opening a separate chat screen
```

For a response that changes the day, the Daily Event list behind the overlay should reflect the updated synchronized state when available.

---

# Example: Late Order Response

```text
User:
[holds Order]
"Move my dentist appointment to Friday afternoon."

User releases
      ↓
Order sent
      ↓
User continues using app / leaves popup
      ↓
Butler finishes later
      ↓
ButlerResponseOverlay appears

"Your dentist appointment has been moved to
Friday at 3:00 PM."

[Call] [Speak] [OK, I see]
```

---

# Example: Immediate Talk Response

```text
User:
[holds Talk]
"Do I have anything important tomorrow?"

      ↓
Butler responds in active conversation overlay

"You have a dentist appointment at 3 PM."

Overlay stays open.
```

No late-response popup is needed because the active Talk conversation is still visible.

---

# Example: Text to Order

```text
Hold Text
    ↓
Speak:
"Shift the planning session to four and tell Minh"

    ↓
STT:
"Shift the planning season to four and tell men"

    ↓
User edits:
"Shift the planning session to 4 PM and tell Minh."

    ↓
Send as Order
    ↓
Normal Order behavior
```

---

# Example: Event Configuration

```text
Today

15:00  Project Review              ⋮
                                      ↓
                               User taps
                                      ↓
┌────────────────────────────────┐
│ Project Review                 │
│ Time       [15:00]             │
│ Reminder   [15 min]            │
│ Speak      [Off]               │
│                                │
│ [Delay] [Skip] [Complete]      │
│                                │
│ [Save]                 [Close] │
└────────────────────────────────┘
```

No Butler request is required.

---

# Initial UI State

Conceptually:

```text
MainScreen
│
├── DailyEventList
│   └── EventDetailPopup?
│
├── ButlerConversationOverlay?
│   ├── live STT
│   ├── messages
│   ├── editor
│   └── active response
│
├── ButlerResponseOverlay?
│   ├── response text
│   ├── Call
│   ├── Speak
│   └── OK
│
└── ButlerControlBar
    ├── Order
    ├── Talk
    └── Text
```

Only the relevant overlay should take interaction focus at a time.

---

# Draft UX Values

Current provisional values:

```text
Butler conversation overlay max height ≈ 70% screen
Order immediate-result hold ≈ 5 seconds
```

These values are intentionally easy to tune after real-device use.

---

# Complete UI Mental Model

```text
                           MAIN SCREEN
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
       DAILY EVENTS       BUTLER OVERLAYS    CONTROL BAR
            │                  │                  │
            │            ┌─────┴─────┐     Order Talk Text
            │            ▼           ▼
            │      Conversation   Late Response
            │         Overlay        Overlay
            │            │           │
            │         Live STT    Response text
            │         Messages     Call / Speak
            │         Editor       OK, I see
            │
            ▼
     Event config button
            │
            ▼
     Event Detail Popup
            │
       Edit / Delay /
      Skip / Complete
```

---

# Guiding UI Principle

Butler should feel like it exists **on top of the user's day**, not inside a separate chat application.

The user should be able to:

- see today's events
- configure an event directly
- hold Order, Talk, or Text
- watch STT live
- see Butler respond without losing context
- receive a late Butler result as a compact overlay
- choose whether to read, hear aloud, or receive the response in a call-like mode

The default behavior should respect context:

```text
Morning Brief       → speak aloud
Good Night Summary  → speak aloud
Butler response     → show text
```

The user remains in control of how Butler speaks.
