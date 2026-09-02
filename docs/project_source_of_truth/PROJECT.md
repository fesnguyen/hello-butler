# Personal Butler

**Document:** Project Overview\
**Version:** 1.0
**Status:** Initial - Source of Truth

------------------------------------------------------------------------

## Vision

Personal Butler is a proactive AI companion that helps users organize,
prepare, execute, and adapt their daily life.

Butler should feel like **one person accompanying the user throughout
the day**, not a collection of productivity features.

The user should not need to think about whether they are creating a
reminder, editing a routine, modifying a schedule, recording progress,
or asking a question.

They simply interact with Butler.

Butler understands the user's life, prepares the day in advance,
introduces the day in the morning, reminds the user when necessary,
responds when spoken to, adapts when plans change, closes the day
naturally, and prepares tomorrow.

The goal is to:

> **Reduce the mental effort required to remember, organize, and execute
> daily life while leaving decisions and control with the user.**

------------------------------------------------------------------------

## Core Product Model

The product follows a continuous daily loop:

``` text
User Context
      │
      ▼
Nightly Planning
      │
      ▼
Tomorrow's Daily Events
      │
      ▼
Morning Introduction
      │
      ▼
Daily Execution
      │
      ├── Reminders
      ├── User actions
      ├── Butler conversations
      └── Plan changes
      │
      ▼
Reality Tracking
      │
      ▼
Good Night Summary
      │
      ▼
Prepare Tomorrow
```

From the user's perspective:

> **Butler knows my plans.\
> Butler tells me what matters.\
> Butler reminds me when necessary.\
> I can talk to Butler whenever something changes.\
> Butler keeps my day synchronized with reality.**

The internal implementation may contain planners, workflows, tools,
repositories, AI models, synchronization systems, and other components.

Those concepts must remain implementation details.

------------------------------------------------------------------------

## The Daily Butler Experience

Butler is designed around the natural rhythm of a day.

### Morning

Butler wakes the user and introduces the upcoming morning.

Example:

> "Good morning. I hope you had a nice sleep. Your Morning Brief will
> start in five minutes."

The initial wake-up message should be short and calm.

After the configured preparation period, Butler speaks the Morning
Brief.

Example:

> "Let's start with some stretching, make the bed, and drink a glass of
> water. You have a 15-minute workout around 6:30. Keeping your daily
> workout going will help you maintain the habit, so try not to skip it
> today.
>
> After that, get ready for work. You plan to leave at 8:15 and work
> starts at 8:45.
>
> You also have a meeting this afternoon..."

The Morning Brief gives the user a useful chronological understanding of
the day.

Timed events are normally introduced in chronological order.

Relevant events without a specific time are introduced after the
scheduled events unless Butler determines that mentioning them earlier
is more useful.

The Morning Brief may contain:

-   Morning routine
-   Exercise
-   Preparation
-   Commute
-   Work
-   Meetings
-   Important reminders
-   One-time plans
-   Temporary context
-   Useful encouragement
-   Other relevant Daily Events

Butler should sound supportive, not mechanical or intrusive.

------------------------------------------------------------------------

## User Context

**User Context** represents information Butler knows about the user's
life and uses when preparing and adapting their days.

User Context is not itself a Daily Event.

### Reference Context

Persistent information about the user.

Examples:

-   The user works as a software developer.
-   The user usually leaves home before 8:15.
-   The user prefers exercising in the morning.
-   The user benefits from being encouraged not to skip workouts.

### Recurring Context

Patterns that normally repeat.

Examples:

-   Wake up at 6 AM every day.
-   Exercise around 6:30 AM.
-   Work from 8:45 AM to 5 PM on weekdays.
-   Prepare for bed around 10:30 PM.

### Temporary Context

Information that applies for a limited period.

Examples:

> "I'm working overtime this week."

> "For the next two weeks I need to stay at work two hours later."

Temporary context may also appear in the client as useful upcoming
information, for example:

> "Working overtime this week."

### One-Time Context

Information that applies once.

Examples:

> "Tomorrow I have a meeting with John at 3 PM."

> "I need to buy groceries on the way home."

------------------------------------------------------------------------

## Daily Events

**Daily Events are concrete occurrences associated with a particular
day.**

Examples:

-   Wake-up message
-   Morning Brief
-   Stretching
-   Drink water
-   Workout
-   Breakfast
-   Leave for work
-   Work period
-   Meeting
-   Reminder
-   Shopping
-   Personal project
-   Good Night Summary

A Daily Event may:

-   have an exact time
-   have an approximate time
-   have no specific time
-   have a duration
-   have a start and end time
-   be completed
-   be skipped
-   be delayed
-   be edited
-   be moved
-   be cancelled
-   be created manually
-   be created by Butler

The fundamental planning relationship remains:

``` text
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

------------------------------------------------------------------------

## Event Ordering

Daily Events should represent the expected progression of the day.

Events with meaningful scheduled times are primarily ordered
chronologically.

Events without a specific time should normally appear after scheduled
events when Butler presents the whole day verbally.

However, presentation order may differ from storage order when doing so
makes the Morning Brief or another interaction more natural.

The purpose is not merely sorting events.

The purpose is helping the user understand:

> **What happens next, what matters later, and what must not be
> forgotten.**

------------------------------------------------------------------------

## Three Primary Butler Interactions

The client provides three persistent ways to interact with the same Butler:

``` text
Order Butler
Talk to Butler
Text Butler
```

These modes describe **how the user wants to interact**, not an absolute interpretation of what the request means. A user may press the "wrong" button; Butler should understand the actual request rather than mechanically treating the selected mode as semantic truth.

``` text
Order → Handle this for me; I may leave immediately.
Talk  → I am here and want an immediate response.
Text  → I want to review exactly what Butler receives before sending.
```

A request from any mode may change state, ask for information, or require clarification.

------------------------------------------------------------------------

## Order Butler

**Order Butler** is optimized for requests the user wants Butler to handle without requiring them to remain in an active conversation.

The user may issue the request, leave the phone, and inspect the result later.

Examples:

> "My meeting moved from 3 PM to 4 PM."

> "Move my workout to this evening."

> "Remind me to buy milk after work."

Although Order strongly suggests action-oriented interaction, Butler interprets the actual request. An Order may therefore result in a state change, an informational response, or clarification.

``` text
User request
     ↓
Butler understands actual meaning
     ↓
Execute / answer / clarify
     ↓
Persist changes when required
     ↓
Produce result
     ↓
Compact or later-visible presentation
```

A completed command may produce a temporary overlay:

``` text
Done.
Your meeting has been moved to 4:00 PM.

[Listen]
```

The user does not need to remain waiting when Butler can complete the interaction without them. Client configuration may control overlay duration, automatic speech, optional playback, and notification behavior.

------------------------------------------------------------------------

## Talk to Butler

**Talk to Butler** is optimized for situations where the user is actively present and expects an immediate response.

> **Speak → wait → receive an immediate answer or confirmation.**

Examples:

> "What is my meeting today about?"

> "What do I have after work?"

> "Move my workout to seven tonight."

> "Do I have enough time for lunch before the meeting?"

Talk is not restricted to informational questions. It may also change Daily Events, User Context, or other Butler-managed state when that is what the user actually requests.

When clarification is required, Butler continues the interaction naturally. The client should prioritize low-latency response delivery and may speak the response immediately according to client speech behavior.

------------------------------------------------------------------------

## Text Butler

**Text Butler** is optimized for requests where the user wants precise control over what Butler receives.

``` text
User speaks
    ↓
Local speech recognition
    ↓
Recognized text appears
    ↓
User reviews / edits
    ↓
Explicit send
    ↓
Butler interprets actual request
```

Text Butler is useful when accuracy matters, speech recognition may be uncertain, the environment is noisy, the request is long or detailed, or a small wording mistake could change the intended action.

After sending, a Text request may result in a state change, an informational response, or clarification. Response presentation follows configured client behavior rather than being determined solely by the Text button.

------------------------------------------------------------------------

## One Butler, Different Interaction Expectations

Order, Talk, and Text are not separate assistants and are not absolute backend intent routes.

``` text
                    USER
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
       ORDER        TALK         TEXT
          │           │           │
          └───────────┼───────────┘
                      ▼
                    BUTLER
                      │
              Understand request
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
        Change       Answer      Clarify
          │           │           │
          └───────────┼───────────┘
                      ▼
             Persist when needed
                      +
               Produce response
```

The selected mode expresses the interaction experience the user expects. The meaning of the request determines what Butler actually does.

Persistence and response are independent outcomes: an interaction may persist state, produce a response, or do both.

------------------------------------------------------------------------

## Daytime Reminders

Butler helps execute the prepared day through local notifications and
reminders.

Examples:

> "Your meeting starts in 15 minutes."

> "Time to leave for work."

> "Don't forget to buy groceries on the way home."

Important reminders should behave similarly to calendar notifications.

By default, daytime reminders **do not speak aloud**.

This is intentional because the user may be:

-   working
-   in a meeting
-   commuting
-   in a public place
-   somewhere speech would be disruptive

The user may configure client-side speech behavior.

A reminder may provide actions such as:

``` text
Dismiss
Done
Delay
Listen
```

The exact client actions may evolve.

------------------------------------------------------------------------

## Proactive Speech

Speech should be used intentionally.

Butler may proactively speak when speech is appropriate, such as:

-   Morning wake-up
-   Morning Brief
-   User-requested playback
-   Configured personal reminders
-   Good Night Summary

Speech should not automatically accompany every notification.

The user controls client-side speech preferences.

The default behavior should minimize interruption.

------------------------------------------------------------------------

## Reality Tracking

The prepared Daily Plan represents Butler's expectation for the day.

As the day progresses, its Daily Events are updated to reflect what actually happened. The Daily Plan therefore becomes the living record of the actual day.

Reality may differ from the original plan.

The user can tell Butler what actually happened:

> "I finished my workout."

> "I'm still at work."

> "The meeting shifted."

> "I'm going home now."

> "I skipped lunch."

> "I can't do this today."

Butler updates the current state and may adjust remaining Daily Events.

``` text
Prepared Day
     ↓
Reality changes
     ↓
User informs Butler
     ↓
Current state updated
     ↓
Remaining events adjusted
     ↓
Client synchronized
```

Butler should preserve useful historical information without treating
every temporary change as permanent User Context.

------------------------------------------------------------------------

## Direct Event Management

The user does not have to communicate with Butler to modify every Daily
Event.

The client allows direct event management.

The user can:

-   create an event
-   edit an event
-   change its time
-   change its duration
-   mark it complete
-   delay it
-   disable it
-   cancel it
-   delete it

These operations should feel immediate.

The client applies the local change first whenever safe.

``` text
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

When connectivity is available, synchronization should happen
immediately.

After synchronization or successful local acceptance, the client may
show a temporary confirmation overlay.

Example:

``` text
Done.
Workout moved to 7:00 PM.
```

This preserves an important product principle:

> **Butler assists the user, but the user remains fully capable of
> controlling their own day.**

------------------------------------------------------------------------

## Offline-First Execution

Butler is designed around offline-first daily execution.

Internet connectivity is required for server-side AI reasoning.

It should **not** be required for executing an already prepared day.

Once Daily Events have synchronized to the client, the client should be
able to continue:

-   showing the Daily Event list
-   triggering alarms
-   triggering notifications
-   playing prepared speech
-   performing text-to-speech
-   recording completed events
-   recording skipped events
-   editing events
-   creating local events
-   deleting events
-   queuing Butler requests
-   queuing synchronization operations

When Internet access returns:

``` text
Queued local changes
        +
Queued user interactions
        ↓
Synchronization
        ↓
Backend
        ↓
Conflict resolution / Butler processing
        ↓
Updated state
        ↓
Client
```

Offline operation is not an exceptional mode.

It is part of the normal architecture.

------------------------------------------------------------------------

## Synchronization Principle

Changes should synchronize as soon as practical.

When online:

> **Local action → immediate synchronization.**

When offline:

> **Local action → queue → synchronize when connectivity returns.**

The user should not need to manually trigger synchronization.

Synchronization must account for changes originating from both:

-   Butler/backend
-   user/client

The system must maintain explicit ownership and conflict rules while
preserving a simple user experience.

------------------------------------------------------------------------

## Main Client Experience

The mobile application intentionally uses a simple primary interface.

The main screen contains one unified view of the user's current day.

Conceptually:

``` text
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
│                                      │
│ [ Order ]   [ Talk ]   [ Text ]      │
└──────────────────────────────────────┘
```

The exact visual design may evolve.

The conceptual requirements remain:

-   one primary screen
-   today's Daily Events are the main content
-   past/completed events become visually secondary
-   upcoming events remain prominent
-   relevant future context may be previewed
-   events can be expanded and edited
-   the three Butler actions remain easily accessible
-   Butler responses may appear as temporary overlays
-   normal interaction should require minimal navigation

------------------------------------------------------------------------

## Upcoming Information

The main screen may show information beyond today's concrete Daily
Events when it helps the user understand upcoming circumstances.

Examples:

-   "Working overtime this week"
-   "Flight next Monday"
-   "Dentist tomorrow at 10 AM"
-   "English class for the next three evenings"

This information may originate from User Context or future plans.

It should not be confused with today's Daily Event list.

Its purpose is awareness, not duplication.

------------------------------------------------------------------------

## Good Night Summary

Before the user's expected bedtime, Butler closes the day.

Butler prepares and speaks a Good Night Summary.

It may include:

-   completed activities
-   missed activities
-   changed plans
-   important accomplishments
-   remaining concerns
-   useful observations
-   tomorrow's important plans

Example:

> "You're almost done for today. You finished your workout and completed
> the workday, although the afternoon meeting moved later than planned.
>
> Tomorrow is another workday and you're still working overtime this
> week. I'll prepare that into tomorrow's plan.
>
> Try to get to sleep on time tonight so tomorrow morning is easier.
> Good night."

The summary should be supportive without pretending to be a medical or
behavioral authority.

------------------------------------------------------------------------

## Nightly Planning

The nightly planning process prepares tomorrow.

Inputs may include:

-   User Context
-   recurring patterns
-   temporary context
-   one-time context
-   tomorrow's known plans
-   today's final Daily Plan
-   actual Daily Event states
-   changes made during the day
-   relevant conversation and user feedback
-   current circumstances

The output is tomorrow's prepared Daily Events.

``` text
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

Tomorrow should therefore begin from an already prepared plan rather
than requiring real-time AI generation at wake-up.

This supports reliability and offline execution.

------------------------------------------------------------------------

## Morning Planning and Offline Reliability

The Morning Brief should normally be prepared during the previous
planning cycle.

This allows the client to already possess the information required for:

-   wake-up speech
-   Morning Brief
-   early reminders
-   morning event execution

before the morning begins.

Therefore, loss of Internet connectivity overnight or during the morning
should not prevent Butler from introducing and executing the prepared
day.

------------------------------------------------------------------------

## Backend and Client Boundary

### Backend Owns

The backend owns intelligence and authoritative planning state.

Responsibilities include:

-   Butler reasoning
-   User Context management
-   interpretation of natural-language requests
-   Daily Event generation
-   nightly planning
-   replanning
-   clarification
-   conflict detection
-   Morning Brief preparation
-   Good Night Summary preparation
-   conversational answers
-   synchronization coordination
-   server-side history

### Client Owns

The mobile client owns interaction and local execution.

Responsibilities include:

-   main user interface
-   Daily Event display
-   direct event editing
-   speech recognition
-   text editing before send
-   text-to-speech
-   local speech playback
-   alarms
-   notifications
-   response overlays
-   local database
-   offline event execution
-   queued changes
-   synchronization
-   client-side speech preferences

The client should not perform core AI planning.

The backend should not be required for basic execution of already
synchronized Daily Events.

------------------------------------------------------------------------

## Response Presentation

Response presentation follows the interaction experience rather than assuming that a button determines the semantic meaning of the request.

An interaction can independently produce:

``` text
Domain persistence
Conversation persistence
Response
```

A command may update a Daily Event and return a confirmation. A question may leave domain state unchanged while still returning an answer and recording conversation history.

### Order Butler

Typical presentation:

- compact confirmation or result
- temporary overlay
- later-visible result when the user has left
- optional Listen action
- clarification when required

### Talk to Butler

Typical presentation:

- immediate conversational response
- low-latency delivery
- spoken response according to client behavior
- immediate confirmation even when the request changed state

### Text Butler

Text primarily controls input accuracy. After explicit send, Butler processes the actual request; it may change state, answer a question, or require clarification. Response presentation follows configured client behavior.

### Reminder

Typical presentation:

- local notification
- silent by default
- optional Listen action

### Morning / Night

Typical presentation:

- proactive local speech
- corresponding Daily Event visible in the Daily Event list

The backend determines what happened and what Butler should communicate.

The client determines how that result is rendered, spoken, overlaid, or notified.

------------------------------------------------------------------------

## Clarification and Safety of Decisions

Butler should not silently invent important information.

When required information is missing, Butler asks.

Example:

> User: "Move my meeting later."

Butler may respond:

> "Sure. What time should I move it to?"

If Butler can safely infer a minor detail, it may do so according to
established product rules.

Consequential or ambiguous changes should remain explicit.

The core rule is:

> **Assist aggressively with execution, but do not take ownership of
> important user decisions.**

------------------------------------------------------------------------

## Simplicity Principle

The product should remain simple from the user's perspective.

The user sees:

``` text
One Butler
One main screen
One day
Three ways to interact
```

They should not have to understand:

-   planner workflows
-   memory systems
-   API endpoints
-   synchronization engines
-   context categories
-   AI providers
-   database schemas
-   event-generation pipelines

Internal complexity exists to make the external experience simpler.

------------------------------------------------------------------------

## Initial Product Scope

The initial product includes:

-   One Butler
-   One primary mobile screen
-   User Context
-   Recurring context
-   Temporary context
-   One-time context
-   Daily Events
-   Direct Daily Event management
-   Nightly planning
-   Prepared Morning Brief
-   Morning wake-up speech
-   Daytime reminders
-   Order Butler
-   Talk to Butler
-   Text Butler
-   Reality tracking
-   Dynamic replanning
-   Good Night Summary
-   Sleep-time encouragement
-   Offline-first event execution
-   Local STT
-   Local TTS
-   Local notifications and alarms
-   Automatic synchronization
-   Temporary Butler response overlays

------------------------------------------------------------------------

## Non-Goals

The initial Butler is not intended to become:

-   a general-purpose chatbot
-   a general search engine
-   a social network
-   an entertainment assistant
-   a smart-home platform
-   a replacement for calendar software in every use case
-   a replacement for the user's judgment
-   a system that requires permanent Internet connectivity

New features should strengthen daily-life planning and execution rather
than expand the product without a clear reason.

------------------------------------------------------------------------

## Product Principles

Future product decisions should preserve these principles:

-   Butler feels like one person.
-   Natural interaction remains primary.
-   The user's day is represented by Daily Events.
-   User Context describes the user's life, not today's concrete
    schedule.
-   Butler prepares tomorrow before tomorrow begins.
-   Morning speech introduces the prepared day.
-   Timed events are presented chronologically when appropriate.
-   Unscheduled information remains visible without distorting the
    timeline.
-   Daytime reminders are silent by default.
-   Speech is intentional rather than constant.
-   Order Butler prioritizes unattended handling and compact or later-visible results.
-   Talk to Butler prioritizes immediate conversational response.
-   Text Butler prioritizes request accuracy and review before send.
-   Interaction mode expresses user expectation, not absolute semantic intent.
-   Butler interprets the actual request even when the selected interaction mode is imperfect.
-   Persistence and response are independent outcomes of an interaction.
-   Direct manual event editing remains available.
-   User actions should feel immediate.
-   Synchronization should happen automatically.
-   Offline execution is a normal requirement.
-   Butler adapts when reality differs from the plan.
-   Good Night Summary closes the daily cycle.
-   The backend owns intelligence.
-   The client owns execution.
-   The user remains in control.
-   The product minimizes cognitive load.
-   Simplicity is preferred over unnecessary feature richness.

------------------------------------------------------------------------

## Source of Truth

`PROJECT.md` is the source of truth for Personal Butler's:

-   purpose
-   behavior
-   user experience
-   core concepts
-   workflows
-   product boundaries
-   interaction model
-   product principles

Technical documents derive from this document.

``` text
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

If implementation conflicts with this document, the conflict should be
identified explicitly rather than treating the existing implementation
as correct.

------------------------------------------------------------------------

## Current Product Mental Model

``` text
                         USER
                           │
           ┌───────────────┼───────────────┐
           │               │               │
         ORDER            TALK            TEXT
           │               │               │
           └───────────────┼───────────────┘
                           ▼
                         BUTLER
                           │
                 Understand user's life
                           │
                           ▼
                     USER CONTEXT
                           │
                           ▼
                    DAILY PLANNING
                           │
                           ▼
                     DAILY EVENTS
                           │
             ┌─────────────┼─────────────┐
             │             │             │
           MORNING         DAY          NIGHT
             │             │             │
        Wake + Brief   Remind / Adapt   Summary
             │             │             │
             └─────────────┼─────────────┘
                           ▼
                         MOBILE
                           │
                  Offline-first execution
                           │
          ┌────────────────┼────────────────┐
          │                │                │
      Notifications     Speech        Event editing
          │                │                │
          └────────────────┼────────────────┘
                           ▼
                      USER REALITY
                           │
                           ▼
                   Synchronize changes
                           │
                           └──────────────► Butler
```

> **Butler prepares the day before it begins, introduces it in the
> morning, quietly helps the user execute it, responds instantly when
> needed, adapts when reality changes, closes the day at night, and
> prepares tomorrow.**
