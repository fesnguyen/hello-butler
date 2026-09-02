# Backend Workflow

**Version:** 1.0  
**Status:** Initial  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/ARCHITECTURE.md`

---

# Purpose

This document defines how backend workflows behave.

It describes:

- the purpose of each workflow
- request and state fields
- LangGraph routing
- database interaction
- response generation
- synchronization behavior
- Daily Plan lifecycle
- Morning Brief and Good Night Summary flow

The most important workflow is `/api/butler/talk`.

---

# Core Mental Model

`/api/butler/talk` is the single conversational entry point into Butler.

The client interaction mode does not directly determine what the request means.

```text
POST /api/butler/talk
          │
          ▼
     Build ButlerState
          │
          ▼
      Load Context
          │
          ▼
   Understand Request
          │
          ▼
   Route by semantics
```

Three different questions must remain separate:

```text
How is the user interacting?
→ order | talk | text

What does the request mean?
→ command | query | clarification

What changes?
→ domain persistence | conversation persistence | no domain mutation

How is the result delivered?
→ compact | immediate | deferred | spoken | text
```

The UI button is a strong interaction hint, not an absolute intent.

---

# Interaction Modes

## Order

Purpose:

> The user wants Butler to handle the request without requiring them to stay in an active conversation.

Typical behavior:

```text
speech / text
    ↓
request
    ↓
Butler processes
    ↓
domain change when needed
    ↓
compact result
    ↓
overlay or later-visible result
```

Order is suitable when the user wants a quick update and may leave the phone immediately.

Examples:

- move tomorrow's workout to the evening
- add groceries after work
- skip today's exercise
- adjust tomorrow around an appointment

Order requests may still require clarification.

---

## Talk

Purpose:

> The user is actively present and expects an immediate conversational response.

Typical behavior:

```text
speech
  ↓
request
  ↓
Butler processing
  ↓
response stream
  ↓
client speaks response
```

Talk may still execute commands.

Example:

```text
Mode: Talk
Request: "Move my workout to seven tonight."

Semantic intent: command
Domain persistence: yes
Response: immediate spoken confirmation
```

Talk may also be a pure query.

Example:

```text
Mode: Talk
Request: "What do I have tonight?"

Semantic intent: query
Domain persistence: no
Response: immediate spoken answer
```

---

## Text

Purpose:

> The user wants maximum control over the exact request before sending it.

Typical behavior:

```text
speech
  ↓
STT
  ↓
editable transcript
  ↓
user correction
  ↓
explicit send
```

After the request reaches the backend, Text does not imply a specific semantic intent.

It may become:

```text
text + command
text + query
text + clarification response
```

Response presentation follows the configured client behavior.

---

# Semantic Routes

LangGraph routes by the meaning of the request rather than the button that produced it.

```text
                         START
                           │
                           ▼
                    LOAD CONTEXT
                           │
                           ▼
                  UNDERSTAND REQUEST
                           │
             ┌─────────────┼─────────────┐
             │             │             │
             ▼             ▼             ▼
          COMMAND         QUERY        CLARIFY
             │             │             │
             ▼             ▼             │
        PLAN ACTION    BUILD ANSWER       │
             │             │             │
             ▼             │             │
       APPLY ACTION        │             │
             │             │             │
             └─────────────┼─────────────┘
                           ▼
                     BUILD RESULT
                           │
                           ▼
                     SAVE HISTORY
                           │
                           ▼
                          END
```

---

# Command Route

Purpose:

> Change Butler-managed state.

Examples:

- move an event
- create a reminder
- skip an event
- update User Context
- change tomorrow's plan
- record a meaningful user preference

Flow:

```text
COMMAND
   │
   ▼
Understand requested change
   │
   ▼
Determine target entity
   │
   ├── User Context
   ├── Daily Plan
   ├── Daily Event
   └── other supported state
   │
   ▼
Load required state
   │
   ▼
Enough information?
   │
   ├── no ─────→ CLARIFY
   │
   └── yes
        │
        ▼
   Propose mutation
        │
        ▼
   Validate mutation
        │
        ▼
   Apply deterministic change
        │
        ▼
      Persist
        │
        ▼
   Build confirmation
```

AI determines what should happen.

Normal application code performs the mutation.

AI should not directly execute SQL or mutate persistence models without validation.

---

# Query Route

Purpose:

> Return information, reasoning, explanation, or advice without changing domain state.

Examples:

- what do I have after work?
- when is my next meeting?
- do I have enough time to buy groceries first?
- summarize my afternoon

Flow:

```text
QUERY
   │
   ▼
Understand information need
   │
   ▼
Determine required context
   │
   ├── User Context
   ├── Daily Plan
   ├── Daily Events
   └── Conversation History
   │
   ▼
Retrieve relevant information
   │
   ▼
Reason / generate answer
   │
   ▼
Build response
   │
   ▼
Save conversation history
```

The Query route is read-only with respect to domain state.

Conversation history may still be persisted.

---

# Clarification Route

Purpose:

> Ask for missing information when Butler cannot safely continue.

Example:

```text
User:
"Move my meeting later."

Known:
meeting = 15:00
requested action = move

Missing:
target time
```

Flow:

```text
COMMAND or QUERY
       │
       ▼
Required information missing
       │
       ▼
     CLARIFY
       │
       ▼
Generate concise question
       │
       ▼
Persist conversation context
       │
       ▼
Return clarification
```

Example response:

```text
"What time would you like me to move it to?"
```

The next request is interpreted with previous conversation context.

Example:

```text
Previous:
"Move my meeting later."

Butler:
"What time would you like me to move it to?"

Current:
"4 PM."

Resolved meaning:
Move meeting → 16:00
```

A separate PlannerSession-style table is not required.

Conversation history provides continuity.

---

# ButlerState Mental Model

`ButlerState` is the working contract shared across LangGraph nodes.

Conceptually:

```text
ButlerState
│
├── Request
│   ├── user_id
│   ├── message
│   └── interaction_mode
│
├── Context
│   ├── conversation_history
│   ├── user_context
│   ├── daily_plan
│   └── relevant_events
│
├── Understanding
│   ├── intent
│   ├── requested_action
│   ├── required_information
│   └── missing_information
│
├── Execution
│   ├── proposed_changes
│   ├── applied_changes
│   └── execution_status
│
└── Result
    ├── response
    ├── response_mode
    ├── changed_entities
    └── requires_follow_up
```

This is a mental grouping, not a requirement that the Python model use nested objects.

---

# ButlerState Field Purposes

## Request

### `user_id`

Purpose:

Identifies the authenticated user whose data is being processed.

Rules:

- derived from authentication
- never trusted from arbitrary request payload data
- used to scope User Context, plans, events, history, and devices

### `message`

Purpose:

The normalized user text sent to Butler.

Sources may include:

- direct text
- speech converted to text
- edited STT transcript

This is the semantic input used by the graph.

### `interaction_mode`

Purpose:

Describes how the user wants to interact with Butler.

Allowed initial values:

```text
order
talk
text
```

It affects delivery expectations and UX behavior.

It must not be treated as absolute semantic intent.

---

# Context Fields

## `conversation_history`

Purpose:

Provides conversational continuity.

Used for:

- resolving follow-up answers
- understanding clarification responses
- preserving recent conversational context
- avoiding interpretation of each message in isolation

Keep loaded history relevant and bounded rather than blindly loading all history.

## `user_context`

Purpose:

Provides durable or temporary information Butler should consider when reasoning.

Examples:

- preferences
- routines
- temporary circumstances
- recurring information
- one-time context

Only relevant context should be supplied to reasoning when practical.

## `daily_plan`

Purpose:

Represents the current relevant day plan.

Depending on the request, this may be:

- today's plan
- tomorrow's plan
- another explicitly requested day

The plan provides day-level context and contains Daily Events.

## `relevant_events`

Purpose:

Provides the subset of Daily Events directly relevant to the current request.

Examples:

- the meeting being moved
- today's remaining events
- tomorrow's exercise event

This avoids forcing every graph node to reason across an entire plan when only a few events matter.

---

# Understanding Fields

## `intent`

Purpose:

Represents the normalized semantic route.

Initial values:

```text
command
query
clarify
```

It describes what Butler must do.

It does not describe which UI button the user pressed.

## `requested_action`

Purpose:

Represents the normalized operation Butler believes the user wants.

Examples:

```text
create_event
update_event
skip_event
delay_event
update_user_context
```

Queries may leave this empty.

## `required_information`

Purpose:

Lists information required to safely complete the current semantic task.

Example:

```text
Action:
move meeting

Required:
event identity
target time
```

## `missing_information`

Purpose:

Contains required information that could not be resolved from:

- the current message
- conversation history
- User Context
- Daily Plan
- relevant Daily Events

If meaningful required information remains missing, routing should move to clarification.

---

# Execution Fields

## `proposed_changes`

Purpose:

Represents structured domain changes suggested by reasoning before they are applied.

Example:

```json
{
  "entity": "daily_event",
  "entity_id": "...",
  "action": "update",
  "changes": {
    "start_time": "16:00"
  }
}
```

Proposed changes are not yet trusted persistence operations.

They must be validated first.

## `applied_changes`

Purpose:

Records changes successfully applied by deterministic application logic.

Used for:

- persistence
- response generation
- sync response
- audit/debug information when needed

## `execution_status`

Purpose:

Represents execution progress or outcome.

Possible initial values may include:

```text
pending
completed
clarifying
failed
```

Keep these as strings.

---

# Result Fields

## `response`

Purpose:

Contains the semantic Butler response.

Examples:

```text
"Done. Your meeting is now at 4 PM."

"You have a meeting at 3 PM and groceries after work."

"What time would you like me to move it to?"
```

The response describes what Butler should communicate.

It does not decide Android UI behavior.

## `response_mode`

Purpose:

Describes the expected delivery style after considering interaction mode and user preferences.

Possible conceptual values:

```text
compact
immediate
deferred
```

Speech versus visual rendering remains primarily a client responsibility.

Typical defaults:

```text
order → compact / deferred
talk  → immediate
text  → configured response behavior
```

## `changed_entities`

Purpose:

Identifies domain entities changed during execution.

Useful for:

- sync
- client refresh
- compact confirmations
- tracing

## `requires_follow_up`

Purpose:

Indicates whether Butler expects additional user input before the original request can be completed.

Typical use:

```text
true  → clarification pending
false → interaction complete
```

---

# Persistence and Response Are Independent

A Butler action may result in persistence, a response, or both.

Examples:

```text
"Move my meeting to 4 PM."

Domain persistence: yes
Conversation persistence: yes
Response: yes
```

```text
"What do I have after lunch?"

Domain persistence: no
Conversation persistence: yes
Response: yes
```

```text
"Remember that I prefer late meetings."

Domain persistence: yes
Conversation persistence: yes
Response: yes
```

Do not equate "response" with "no write" or "command" with "write only".

---

# Domain Persistence

Domain persistence changes product state.

Examples:

- Daily Plan
- Daily Event
- User Context
- device or sync state

Domain mutation is performed by deterministic application code after validation.

---

# Conversation Persistence

Conversation persistence stores interaction history.

It may occur for both Query and Command routes.

Conceptually:

```text
user message
    ↓
conversation_history
    ↓
Butler result
    ↓
conversation_history
```

Conversation persistence is separate from domain mutation.

---

# Response Delivery

LangGraph decides:

```text
What happened?
What should Butler communicate?
```

Application logic decides:

```text
What interaction behavior applies?
```

The client decides:

```text
How should this be rendered or spoken?
```

Separation:

```text
LangGraph
→ semantic result

Application
→ delivery metadata and interaction behavior

Client
→ overlay, screen, text, TTS, notification
```

Examples:

```text
Order + completed command
→ compact confirmation
→ overlay or later-visible result
```

```text
Talk + query
→ immediate response
→ streamed/spoken by client
```

```text
Text + command
→ precise request
→ response presentation follows configured client preference
```

---

# Talk Endpoint Detailed Flow

```text
POST /api/butler/talk
        │
        ▼
Authenticate request
        │
        ▼
Normalize message
        │
        ▼
Build initial ButlerState
        │
        ▼
Load conversation history
        │
        ▼
Load relevant User Context
        │
        ▼
Load relevant Daily Plan / Events
        │
        ▼
UNDERSTAND REQUEST
        │
        ├── infer semantic intent
        ├── resolve references
        ├── determine required information
        └── detect missing information
        │
        ▼
Route
   ┌────┼────┐
   │    │    │
   ▼    ▼    ▼
COMMAND QUERY CLARIFY
   │    │    │
   │    │    └── build clarification
   │    │
   │    └── reason over loaded context
   │         └── build answer
   │
   └── produce structured mutation proposal
        ├── validate
        ├── execute
        └── persist
             │
             ▼
        BUILD RESULT
             │
             ▼
     persist conversation
             │
             ▼
 determine delivery metadata
             │
             ▼
        return response
```

---

# Daily Plan Lifecycle

A Daily Plan represents one day and evolves as reality changes.

```text
Night before
    ↓
Generate tomorrow's Daily Plan
    ↓
Generate Daily Events
    ↓
Prepare Morning Brief
    ↓
Persist
    ↓
Sync to client
    ↓
Day begins
    ↓
Events are executed / edited / delayed / skipped / completed
    ↓
Plan increasingly reflects the actual day
    ↓
Night
    ↓
Read actual final day state
    ↓
Generate Good Night Summary
    ↓
Use relevant context + actual day
    ↓
Prepare tomorrow's Daily Plan
```

The updated Daily Plan is the primary representation of what actually happened during the day.

A separate "actual day" entity is not required.

---

# Daily Planning Flow

Purpose:

Prepare a practical Daily Plan before the day begins.

Inputs may include:

- User Context
- target date
- known commitments
- existing future events
- recent relevant conversation
- recent actual day information

Flow:

```text
Target date
    +
User Context
    +
Known commitments
    +
Relevant recent state
        │
        ▼
     Planner
        │
        ▼
   Daily Plan
        │
        ▼
  Daily Events
        │
        ▼
 Morning Brief
        │
        ▼
     Persist
        │
        ▼
      Sync
```

Daily Events remain flexible and may represent:

- Morning Brief
- meeting
- reminder
- exercise
- work
- grocery task
- custom activity
- Good Night Summary

---

# Morning Brief Flow

Purpose:

Give the user a useful spoken introduction to the day.

The Morning Brief is represented as a Daily Event.

```text
Tomorrow plan prepared
        │
        ▼
Generate Morning Brief content
        │
        ▼
DailyEvent
type = "morning_brief"
speak_aloud = true
        │
        ▼
Persist
        │
        ▼
Sync to client
        │
        ▼
Client schedules local execution
        │
        ▼
Morning
        │
        ▼
Client speaks prepared content
```

The backend does not need to be online at the exact playback time if the event was already synchronized.

---

# Good Night Summary Flow

Purpose:

Review the actual day, help the user close it, and provide context for tomorrow.

```text
Today's final Daily Plan
        +
Actual Daily Event states
        +
Relevant conversation
        +
Relevant User Context
        │
        ▼
Generate Good Night Summary
        │
        ▼
DailyEvent
type = "good_night_summary"
speak_aloud = true
        │
        ▼
Persist
        │
        ▼
Sync
        │
        ▼
Client speaks summary
```

The night flow may then continue into tomorrow's planning.

```text
Actual day
   ↓
Good Night Summary
   ↓
Relevant learning/context
   ↓
Tomorrow Daily Plan
   ↓
Tomorrow Daily Events
   ↓
Tomorrow Morning Brief
```

---

# Direct Event Update Flow

Direct client edits do not need AI unless interpretation is required.

Example:

```text
User edits event time directly in UI
        │
        ▼
Update local DB immediately
        │
        ▼
Queue sync operation
        │
        ▼
Backend sync API
        │
        ▼
Authenticate
        │
        ▼
Check version
        │
        ├── current → apply
        └── stale   → conflict handling
        │
        ▼
Persist server state
        │
        ▼
Return reconciled state
```

This keeps direct CRUD deterministic and fast.

---

# Guiding Rule

The `/api/butler/talk` workflow should preserve four independent concepts:

```text
Interaction mode
→ How the user wants to interact

Semantic intent
→ What the user actually means

Persistence
→ What state changes

Response delivery
→ How the result reaches the user
```

Do not collapse these concepts into one routing field.

The graph should reason about meaning and outcome.

Application code should perform validated state changes and determine interaction behavior.

The client should control presentation, speech, overlays, and notifications.
