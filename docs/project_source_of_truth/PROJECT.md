# Personal Butler

**Document:** Project Overview  
**Version:** 3.0  
**Status:** Draft — Source of Truth

---

## 1. Vision

Personal Butler is a proactive AI companion that helps users organize, plan, and execute their daily life.

Butler should feel like **one person the user can talk to**, not a collection of productivity features.

The user should not need to think about whether they are creating a schedule, routine, reminder, task, or plan. They simply tell Butler what is happening, what they want, what has changed, or what they have done.

Butler understands the user's life, prepares the day, adapts to changes, and helps the user move through it.

The goal is to **reduce cognitive load**, not replace the user's decisions.

---

## 2. Core Product Model

The product is built around a simple loop:

> **The user talks to Butler.  
> Butler understands the user's life.  
> Butler prepares the day.  
> Butler helps execute the day.  
> Butler learns from what happened and prepares tomorrow.**

The user interacts with **one Butler** through one primary conversational API.

Internally, Butler may use multiple components, workflows, tools, and reasoning steps. These are implementation details and should not become part of the user's mental model.

---

## 3. Butler Interaction

The user talks to Butler naturally using voice or text.

Examples:

- "I wake up at 6 every day."
- "I usually exercise at 6:30."
- "Tomorrow I have a meeting with John at 3."
- "For the next two weeks, I need to work two hours overtime every weekday."
- "I arrived at the office."
- "I finished my workout."
- "I can't do the park today."
- "Move everything after 6 PM."
- "Remind me to buy groceries on my way home."

All of these are interactions with the same Butler.

The user should not need to choose a feature-specific operation.

Conceptually:

```text
User
  ↓
Butler
  ↓
Understand
  ↓
Extract / Update User Context
  ↓
Plan / Modify Daily Events
  ↓
Respond
```

Butler may ask clarification when the user's intention or required information is unclear.

---

## 4. User Context

**User Context** is information about the user's life that Butler stores and uses to support the user's life and plan future days.

User Context is extracted and maintained by Butler from natural conversation.

It is not the Daily Plan and it is not a concrete event.

### 4.1 Reference

Persistent information about the user that may be updated over time.

Examples:

- The user works as a software developer.
- The user enjoys personal projects.
- The user prefers morning exercise.
- The user needs preparation time before leaving home.

### 4.2 Recurring Routines

Recurring patterns describing how the user normally lives.

Examples:

- Wake up at 6 AM every day.
- Exercise in the morning.
- Work from 8:45 AM to 5 PM on workdays.
- Work on personal projects on days off when there is no other plan.

Routines may apply to:

- Every day
- Specific weekdays
- Workdays
- Days off
- Other recurring conditions

They are persistent and can be updated whenever the user's life changes.

### 4.3 Temporary / Period Context

Information that applies for a limited period.

Examples:

> "For the next two weeks, I need to work two hours overtime every weekday."

> "For the next three days, I have English class at 7 PM."

Temporary context expires when its applicable period ends.

### 4.4 One-Time Context

Information that happens once or applies to a specific date.

Examples:

> "I have a meeting with John tomorrow at 3 PM."

> "Next Tuesday I want to go to the beach."

After the relevant date passes, it no longer needs to remain active planning context.

---

## 5. Daily Events

**Daily Events are the concrete things Butler expects to happen during a specific day.**

Everything that needs to appear in the user's day can be represented as a Daily Event.

Examples:

- Wake up
- Morning Brief
- Stretching
- Drinking water
- Morning exercise
- Shower
- Breakfast
- Going to work
- Meeting
- Personal project
- Important task
- Shopping
- Going to the park
- Other reminders
- Good Night Summary

A Daily Event may:

- happen once
- have a specific time
- occupy a period
- have a duration
- be completed
- be skipped
- be delayed
- be modified
- be cancelled

The distinction is:

> **User Context describes the user's life.  
> Daily Events describe what Butler plans for a particular day.**

There is no fundamental `Routine → Event` domain relationship.

The fundamental planning relationship is:

```text
User Context
     +
Known plans
     +
Date / calendar
     +
Current circumstances
     ↓
   Butler
     ↓
Daily Events
```

---

## 6. Daily Planning

Butler prepares the next day's Daily Events during the nightly planning process.

The planning process considers:

- User Context
- Recurring routines
- Temporary context
- One-time context
- Existing plans
- User preferences
- Time constraints
- Previous-day information
- Current changes
- The next day's date and circumstances

The result is a concrete plan for tomorrow.

```text
User Context
     +
Tomorrow's known plans
     +
Relevant previous-day information
     ↓
   Butler
     ↓
Tomorrow's Daily Events
```

Daily Events are prepared as a coherent day rather than being independently generated by feature-specific systems.

---

## 7. Daily Planning Cycle

Butler continuously maintains the user's day.

### Night — Prepare Tomorrow

Before the user goes to sleep, Butler:

1. Understands what happened today.
2. Summarizes the day.
3. Uses relevant information from today.
4. Considers the user's current User Context.
5. Considers tomorrow's known plans.
6. Applies relevant recurring patterns.
7. Generates tomorrow's Daily Events.
8. Prepares tomorrow's Morning Brief.

### Morning — Introduce the Day

At the user's configured wake-up time, Butler speaks the Morning Brief.

The user learns what is important and what the day looks like.

### During the Day — Execute and Adapt

The client executes the prepared Daily Events.

The user can tell Butler what has happened or what has changed.

Butler updates the current state and adjusts the remaining Daily Events when necessary.

### Evening — Summarize and Prepare

Before bedtime, Butler summarizes the day and uses the available information to prepare tomorrow.

```text
                 NIGHT
                   │
                   ▼
          Prepare tomorrow
                   │
                   ▼
                MORNING
                   │
                   ▼
            Morning Brief
                   │
                   ▼
                  DAY
                   │
        ┌──────────┴──────────┐
        │                     │
   Daily Events          User updates
        │                     │
        └──────────┬──────────┘
                   ▼
          Adjust remaining day
                   │
                   ▼
                EVENING
                   │
                   ▼
          Good Night Summary
                   │
                   ▼
          Prepare tomorrow
```

---

## 8. Morning Brief

The Morning Brief is a Daily Event that occurs at the beginning of the day.

Butler speaks aloud to wake the user and introduce the day.

It may include:

- Greeting
- Useful morning guidance
- Important events
- Morning activities
- Meetings
- Reminders
- Preparation information
- Other relevant information from today's Daily Events

Example:

> "Good morning. It's time to get up. You have your morning workout at 6:30, breakfast at 7:45, and work starts at 8:45. You also have a meeting with John at 3 PM. Don't forget to stop by the supermarket on your way home."

The Morning Brief is generated from the prepared Daily Events and relevant User Context.

The client performs speech playback locally.

---

## 9. During the Day

Butler helps the user through the day using the prepared Daily Events.

Examples:

> "Your workout is coming up in 30 minutes."

> "Nice work finishing your morning workout. It's time to get ready for your shower."

> "Don't forget to stop by the supermarket for groceries."

Butler should provide useful guidance without becoming intrusive.

The user can also proactively talk to Butler at any time.

---

## 10. User Feedback and Reality Tracking

The original plan may differ from reality.

The user should be encouraged to tell Butler what has actually happened.

Examples:

- "I got it."
- "I arrived at the office."
- "I finished my workout."
- "I skipped breakfast."
- "I'm still working."
- "I'm going home now."
- "I can't do this today."
- "Move this to tomorrow."

Butler uses these updates to maintain an accurate understanding of the current day.

When reality changes, Butler may adjust the remaining Daily Events.

```text
Prepared Daily Events
        ↓
What actually happened
        ↓
User feedback
        ↓
Butler updates current state
        ↓
Adjust remaining Daily Events
        ↓
Continue the day
```

Information learned from the day may also influence future User Context and future planning when appropriate.

---

## 11. Good Night Summary

Before the user's expected bedtime, Butler generates a Good Night Summary.

It may summarize:

- Completed events
- Missed events
- Changed events
- Important accomplishments
- Relevant observations
- Remaining items
- Useful information for tomorrow

Butler speaks the summary aloud and closes the day naturally.

Example:

> "You've had a productive day. You finished your workout, made it through your workday, and picked up the groceries. Tomorrow is another workday, so I'll prepare your usual morning routine. Good night."

The summary is also an input to the next planning cycle.

---

## 12. Clarification and Confirmation

Butler should be helpful without silently making consequential decisions on behalf of the user.

When important information is missing or the user's intention is ambiguous, Butler asks for clarification.

Example:

> "I want to exercise tomorrow."

Butler:

> "Sure. What time would you like to exercise?"

When a change is significant, Butler should make the intended interpretation clear before applying it.

The exact confirmation policy may evolve as Butler becomes more capable.

The core principle remains:

> **Do not silently invent important information or make consequential decisions for the user.**

---

## 13. Backend and Client Boundary

### Backend

The backend owns intelligence and planning.

It is responsible for:

- Butler reasoning
- User Context extraction and management
- Daily Event generation
- Daily planning
- Conflict detection
- Clarification
- Planning responses
- Morning Brief generation
- Good Night Summary generation
- Synchronization

### Client

The client owns local execution.

It is responsible for:

- User interface
- Voice input
- Speech-to-text
- Local storage
- Notifications
- Alarms
- Text-to-speech
- Speech playback
- Offline execution
- Local event execution
- Synchronization

The client does not perform core planning or reasoning.

---

## 14. Offline Principle

Once the client has received the information required to execute the current Daily Plan, passive daily interactions should continue working without Internet connectivity.

The client should be able to:

- Trigger scheduled notifications
- Play prepared speech
- Execute local event interactions
- Record user actions
- Queue user requests

When connectivity returns, queued information can be sent to Butler for processing.

Internet connectivity is required for Butler's AI reasoning, but should not be required for basic execution of an already prepared day.

---

## 15. Simplicity Principles

The user should not need to understand the internal system.

The user should not need to think about:

- APIs
- Schedule objects
- Reminder objects
- Planner nodes
- Workflow graphs
- Database entities
- AI tools

The user talks to Butler.

Butler handles the complexity internally.

The architecture should follow the same principle:

> **Simple external model, well-structured internal implementation.**

---

## 16. Product Scope

The initial product focuses on:

- One Butler interaction
- User Context
- Recurring routines as User Context
- Daily Events
- Nightly Daily Planning
- Morning Brief
- Daytime guidance and reminders
- User progress / reality updates
- Evening Good Night Summary
- Tomorrow planning
- Voice and text interaction
- Offline execution
- Synchronization

---

## 17. Non-Goals

The initial product is not intended to become:

- A general-purpose chatbot
- A search engine
- A social platform
- An entertainment assistant
- A smart-home controller
- A replacement for user decision-making

Features should support the core purpose of helping users manage and execute daily life.

---

## 18. Product Principles

Future decisions should reinforce these principles:

- Butler is one person from the user's perspective.
- Natural language is the primary interaction model.
- One primary conversational API is the user's entry point.
- User Context describes the user's life and supports future planning.
- Routines are a type of User Context.
- Daily Events describe concrete occurrences on a specific day.
- Daily Events are generated from User Context and current circumstances.
- The next day is prepared during the nightly planning cycle.
- The Morning Brief introduces the prepared day.
- Butler adapts the remaining day when reality changes.
- Users are encouraged to tell Butler what has happened.
- The Good Night Summary closes the day and supports tomorrow's planning.
- The backend owns reasoning and planning.
- The client owns local execution.
- Passive execution should work offline.
- Butler should reduce cognitive load.
- Simplicity is preferred over unnecessary feature richness.
- Butler assists the user without taking control.

---

## 19. Source of Truth

`PROJECT_OVERVIEW.md` is the **source of truth for product behavior and intended system behavior**.

It defines:

- Product purpose
- Product behavior
- Core concepts
- User experience
- Workflows
- Product boundaries
- Core rules
- Product direction

Technical documents such as Architecture, Backend, Database, and Engineering are derived from this document.

They explain **how the system implements the product**, not what the product fundamentally is.

When the product changes:

```text
Product idea / user feedback
        ↓
PROJECT_OVERVIEW.md
        ↓
Review impact
        ↓
Architecture
        ↓
Backend
        ↓
Database
        ↓
Implementation
```

The coding agent should use the approved technical documents together with this source of truth.

If the existing code conflicts with the project specification, the conflict must be identified and reviewed rather than silently accepting the existing implementation as correct.

---

## 20. Current Product Model

The simplest mental model of Butler is:

```text
                         USER
                           │
                           │ natural conversation
                           ▼
                        BUTLER
                           │
                           ▼
                    USER CONTEXT
                           │
              ┌────────────┼────────────┐
              │            │            │
          References    Routines    Temporary /
                                   One-time Context
              │            │            │
              └────────────┼────────────┘
                           ▼
                    DAILY PLANNING
                           │
                           ▼
                     DAILY EVENTS
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
          MORNING        DAYTIME      EVENING
           BRIEF        GUIDANCE       SUMMARY
              │            │            │
              └────────────┼────────────┘
                           ▼
                         CLIENT
                           │
                    Local execution
                           │
                           ▼
                     User feedback
                           │
                           └──────► Butler
```

> **The user talks to Butler.  
> Butler understands the user's life.  
> User Context captures what matters.  
> Butler prepares the day's Daily Events.  
> The client helps execute them.  
> The user tells Butler what actually happened.  
> Butler adapts the remaining day, summarizes the day, and prepares tomorrow.**