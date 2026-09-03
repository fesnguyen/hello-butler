# Personal Butler

**Document:** Project Overview
**Version:** 1.1
**Status:** Initial - Source of Truth

---

## Vision

Personal Butler is a proactive AI companion that helps users organize, prepare, execute, and adapt their daily life.

Butler should feel like **one person accompanying the user throughout the day**, not a collection of productivity features.

The user should not need to think about whether they are creating a reminder, editing a routine, modifying a schedule, recording progress, or asking a question. They simply interact with Butler.

The goal is to:

> **Reduce the mental effort required to remember, organize, and execute daily life while leaving decisions and control with the user.**

---

## Core Product Model

```text
User Context
      ↓
Nightly Planning
      ↓
Tomorrow's Daily Events
      ↓
Morning Introduction
      ↓
Daily Execution
      ↓
Reality Tracking
      ↓
Good Night Summary
      ↓
Prepare Tomorrow
```

Butler knows the user's plans, tells them what matters, reminds them when necessary, responds when something changes, and keeps the day synchronized with reality.

---

## Accounts and Authentication

Personal Butler is account-based so the user's plans, context, conversation history, and synchronized state remain associated with the correct person across sessions and devices.

Initial account entry supports:

```text
Email + password
├── Register
└── Login

Google
└── Sign in with Google
```

The user should normally authenticate once and remain signed in across normal application restarts until the session expires, is revoked, or the user logs out.

Authentication must protect the user's private Butler data without introducing enterprise account concepts that the product does not need. The initial product has no roles, organizations, or administrator permission model.

A user's authentication method is an account-entry mechanism, not their Butler identity. A Butler account may support linked authentication methods when explicit account-linking behavior is introduced.

---

## User Context

**User Context** represents information Butler knows about the user's life and uses when preparing and adapting their days.

User Context is not itself a Daily Event.

Initial context forms include:

- Reference Context — persistent facts and preferences
- Recurring Context — repeating patterns and routines
- Temporary Context — information applying for a limited period
- One-Time Context — information applying once

Examples include work hours, preferred exercise time, temporary overtime, tomorrow's meeting, or a one-time errand.

---

## Daily Events

**Daily Events are concrete occurrences associated with a particular day.**

Examples include wake-up, Morning Brief, exercise, work, meetings, reminders, shopping, personal projects, and Good Night Summary.

A Daily Event may have an exact, approximate, or absent time; may have a duration; and may be completed, skipped, delayed, edited, moved, cancelled, manually created, or created by Butler.

```text
User Context
      +
Known Plans
      +
Current Daily State
      +
Date / Time
      +
Recent User Feedback
      ↓
    Butler
      ↓
Daily Events
```

Timed events are primarily chronological. Unscheduled information should remain visible without distorting the timeline.

---

## Butler Interaction

The client exposes three persistent controls:

```text
Order
Talk
Text
```

There are only two final interaction expectations sent to the backend:

```text
Order → Handle this for me; I may leave immediately.
Talk  → I am here and want an immediate response.
```

**Text is an input-preparation path, not a third backend interaction mode.**

```text
Text
 ↓
Speech recognition
 ↓
Review / edit exact message
 ↓
Send as Order or Talk
```

The selected interaction expectation is not semantic intent. Butler interprets the actual request, which may change state, ask for information, or require clarification.

---

## Order Butler

Order is optimized for requests the user wants Butler to handle without requiring them to remain in an active conversation.

The user may issue a request, leave the phone, and inspect the result later. A result may be shown immediately or later depending on when it becomes available.

Order may still answer a question or ask for clarification when that is what the request requires.

---

## Talk to Butler

Talk is optimized for situations where the user is actively present and expects an immediate response.

Talk may answer questions, change Daily Events or User Context, or ask for clarification. It is not restricted to read-only conversation.

---

## Response Presentation

The backend produces the semantic result and canonical response text.

The client decides how the user receives that response.

```text
Backend
→ response text + result metadata

Client
→ text overlay
→ Speak
→ Call-style receive
→ dismiss / acknowledge
```

Ordinary Butler responses default to text so the application does not unexpectedly speak private content aloud.

Morning Brief and Good Night Summary normally default to proactive local speech and remain configurable by the user.

---

## Daytime Reminders

Butler helps execute the prepared day through local notifications and reminders.

Daytime reminders are silent by default because the user may be working, commuting, in a meeting, or in public. The user may explicitly listen or configure different speech behavior.

Possible reminder actions include Dismiss, Done, Delay, and Listen.

---

## Reality Tracking

The prepared Daily Plan represents Butler's expectation for the day. As reality changes, Daily Events are updated so the plan becomes the living record of what actually happened.

```text
Prepared Day
     ↓
Reality changes
     ↓
User informs Butler / edits directly
     ↓
Current state updated
     ↓
Remaining events adjusted when needed
     ↓
Client synchronized
```

Butler should preserve useful history without treating every temporary change as permanent User Context.

---

## Direct Event Management

The user remains fully capable of managing visible Daily Events without talking to Butler.

Direct actions include create, edit, reschedule, complete, delay, skip, cancel, and delete.

```text
User changes event
      ↓
Local state updated
      ↓
UI updates immediately
      ↓
Synchronization queued
      ↓
Server synchronized
```

Direct event management is deterministic and does not require AI.

---

## Offline-First Execution

Internet connectivity is required for novel server-side AI reasoning, but it should not be required to execute an already prepared day.

Once synchronized, the client should continue to show Daily Events, trigger alarms and notifications, perform prepared TTS, record event changes, edit/create/delete local events, and queue synchronization or Butler requests while offline.

When connectivity returns, queued work synchronizes automatically.

Offline operation is part of the normal architecture, not an exceptional mode.

---

## Main Client Experience

The mobile application has one primary screen centered on today's Daily Events.

```text
┌──────────────────────────────────────┐
│ Today                                │
│                                      │
│ 06:00  Wake up              Done     │
│ 06:05  Morning Brief                 │
│ 06:30  Workout                       │
│ 08:15  Leave for work                │
│ 15:00  Meeting                       │
│ ...                                  │
│                                      │
│ Upcoming                             │
│ • Working overtime this week         │
│ • Dentist tomorrow at 10 AM          │
│                                      │
│ [ Order ]   [ Talk ]   [ Text ]      │
└──────────────────────────────────────┘
```

Past/completed events become visually secondary, upcoming events remain prominent, relevant future context may be previewed, and events remain directly configurable.

Butler interaction appears over the current day rather than navigating the user into a separate chat application.

---

## Good Night Summary and Nightly Planning

Before the expected bedtime, Butler closes the day with a Good Night Summary based on actual event outcomes and relevant context.

Nightly planning then prepares tomorrow from today's reality, User Context, recurring patterns, temporary/one-time information, tomorrow's known plans, and relevant user feedback.

```text
Today's Reality
      +
User Context
      +
Tomorrow's Known Plans
      ↓
Nightly Butler Planning
      ↓
Tomorrow's Daily Events
      +
Tomorrow's Morning Brief
```

The Morning Brief should normally be prepared before morning so the client can execute the start of the day even if connectivity is unavailable.

---

## Backend and Client Boundary

### Backend Owns

- authenticated identity and account authentication
- Butler reasoning
- User Context management
- natural-language interpretation
- Daily Event generation
- nightly planning and replanning
- clarification
- conflict detection
- Morning Brief and Good Night Summary content
- conversational answers
- synchronization coordination
- server-side history and authoritative synchronized state

### Client Owns

- account entry UI and local session handling
- main UI and Daily Event display
- direct event editing
- speech recognition and text editing
- TTS and local speech playback
- alarms and notifications
- response presentation
- local database and offline execution
- queued changes and synchronization
- client-side presentation preferences

The client should not perform core AI planning. The backend should not be required for basic execution of already synchronized Daily Events.

---

## Clarification and Decision Safety

Butler should not silently invent important information.

When required information is missing, Butler asks. Minor details may be inferred only when established product rules make doing so safe.

> **Assist aggressively with execution, but do not take ownership of important user decisions.**

---

## Initial Product Scope

The initial product includes:

- Email/password registration and login
- Google sign-in
- One Butler
- One primary mobile screen
- User Context
- Daily Events and direct event management
- Nightly planning and prepared Morning Brief
- Morning wake-up speech
- Daytime reminders
- Order and Talk interaction expectations
- Text review/edit input path
- Reality tracking and dynamic replanning
- Good Night Summary
- Offline-first event execution
- Local STT and TTS
- Local notifications and alarms
- Automatic synchronization
- Butler response overlays

---

## Non-Goals

The initial Butler is not intended to become:

- a general-purpose chatbot or search engine
- a social network
- an entertainment assistant
- a smart-home platform
- an enterprise identity/authorization system
- a replacement for calendar software in every use case
- a replacement for the user's judgment
- a system that requires permanent Internet connectivity

New features should strengthen daily-life planning and execution rather than expand the product without a clear reason.

---

## Product Principles

Future product decisions should preserve these principles:

- Butler feels like one person.
- Natural interaction remains primary.
- Authentication protects one user's private Butler state without exposing enterprise complexity.
- The user's day is represented by Daily Events.
- User Context describes the user's life, not today's concrete schedule.
- Butler prepares tomorrow before tomorrow begins.
- Speech is intentional rather than constant.
- Order prioritizes unattended handling.
- Talk prioritizes immediate conversational response.
- Text prioritizes accurate input and ends as Order or Talk.
- Interaction expectation is not semantic intent.
- Direct manual event editing remains available.
- User actions should feel immediate.
- Synchronization happens automatically.
- Offline execution is a normal requirement.
- The backend owns intelligence and authoritative synchronized state.
- The client owns local execution and presentation.
- The user remains in control.
- Simplicity is preferred over unnecessary feature richness.

---

## Source of Truth

`PROJECT.md` is the source of truth for Personal Butler's purpose, behavior, user experience, core concepts, product boundaries, interaction model, authentication experience, and product principles.

Technical documents derive from this document.

```text
PROJECT.md                 ENGINEERING.md
Product behavior           Engineering practice
      │                           │
      └──────────────┬────────────┘
                     ↓
          backend/ARCHITECTURE.md
          backend/WORKFLOW.md
          client/ARCHITECTURE.md
          client/WORKFLOW.md
          client/ui/...
                     ↓
               Implementation
```

If implementation conflicts with this document, identify the conflict explicitly rather than treating the implementation as correct.
