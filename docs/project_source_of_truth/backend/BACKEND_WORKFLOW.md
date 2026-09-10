# Backend Workflow

**Version:** 1.3
**Status:** Initial  
**Authority:** Derived from `PROJECT.md`, `ENGINEERING.md`, and `backend/BACKEND_ARCHITECTURE.md`

---

# Purpose

This document defines how backend workflows behave.

It describes:

- authentication
- Butler request handling
- LangGraph routing
- state fields
- persistence
- synchronization
- Daily Plan lifecycle
- Morning Brief and Good Night Summary generation

The most important Butler workflow is `/api/butler/talk`.

---

# Authentication Workflows

Authentication is deterministic backend behavior and does not involve Butler or
AI reasoning.

Supported entry points are conceptually:

```text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/google
POST /api/auth/refresh
POST /api/auth/logout
```

Exact route naming may evolve without changing the workflow contract.

## Email Registration

```text
email + password
      ↓
normalize and validate email
      ↓
validate password requirements
      ↓
email already registered for password login?
      ├── yes → reject
      └── no
           ↓
      hash password with Argon2id
           ↓
      create User
           +
      create password AuthIdentity
           ↓
      create refresh session
           ↓
      issue access + refresh tokens
```

Registration must not persist a plaintext or reversibly encrypted password.

## Email Login

```text
email + password
      ↓
normalize email
      ↓
resolve password AuthIdentity
      ↓
verify Argon2id password hash
      ├── invalid → generic authentication failure
      └── valid
           ↓
      create refresh session
           ↓
      issue access + refresh tokens
```

Authentication failures should not reveal whether a particular account exists.

## Google Login

```text
Google ID token from client
      ↓
verify token with Google
      ├── signature
      ├── issuer
      ├── audience
      └── expiry
      ↓
read stable Google subject (`sub`)
      ↓
find google AuthIdentity(provider_subject = sub)
      │
      ├── found → resolve User
      │
      └── not found
            ↓
         create User
            +
         create google AuthIdentity
      ↓
create refresh session
      ↓
issue access + refresh tokens
```

Google identity is keyed by the verified provider subject, not by email alone.

If the verified Google email matches an existing password account but no Google
identity is already linked, do not silently merge the accounts. Explicit account
linking can be added later as an authenticated workflow.

## Access Token Authentication

Protected requests use:

```text
Authorization: Bearer <access token>
              ↓
verify signature
              ↓
verify expiry / required claims
              ↓
resolve internal user identity
              ↓
execute endpoint as that user
```

The authenticated user is injected/resolved at the API boundary. Request bodies
do not establish ownership by supplying `user_id`.

## Token Refresh

```text
refresh token
      ↓
hash presented secret
      ↓
find active refresh session
      ↓
validate expiry + revocation + rotation state
      ├── invalid/reused → revoke session → reject
      └── valid
           ↓
      consume current refresh token
           ↓
      generate replacement refresh token
           ↓
      store replacement hash / rotation metadata
           ↓
      issue new access + refresh tokens
```

Refresh rotation is atomic so a refresh token cannot be successfully consumed
more than once.

## Logout

```text
current refresh token/session
      ↓
resolve session
      ↓
revoke session
      ↓
client deletes local tokens
```

Access tokens remain intentionally short-lived; logout does not require a global
access-token denylist.

---

# Core Butler Mental Model

`/api/butler/talk` is the single conversational entry point into Butler.

The backend receives:

```text
message
interaction_mode = order | talk
```

The client may have captured that message by direct speech or by the Text
editing flow, but that input method is no longer a backend concern.

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

Keep these concerns separate:

```text
How is the user interacting?
→ order | talk

What does the request mean?
→ command | query | clarification

What changes?
→ domain persistence | conversation persistence | no domain mutation

What does Butler return?
→ text response + semantic result metadata
```

The client decides how that text is presented.

---

# Interaction Modes

## Order

Purpose:

> The user wants Butler to handle the request without requiring them to stay in an active conversation.

Typical behavior:

```text
final message
    ↓
request
    ↓
Butler processes
    ↓
domain change when needed
    ↓
text result
```

The client may show the result immediately or later as a response overlay.

Order requests may still require clarification.

---

## Talk

Purpose:

> The user is actively present and expects an immediate conversational response.

Typical behavior:

```text
final message
    ↓
request
    ↓
Butler processing
    ↓
text response
```

Talk may still execute commands.

Example:

```text
Mode: Talk
Request: "Move my workout to seven tonight."

Semantic intent: command
Domain persistence: yes
Response text: "Done. I moved your workout to 7 PM."
```

Talk may also be a pure query.

```text
Mode: Talk
Request: "What do I have tonight?"

Semantic intent: query
Domain persistence: no
Response text: "You have exercise at 7 PM and groceries afterward."
```

---

# Text Input Is Client-Side

Text is not a backend interaction mode.

Client flow:

```text
speech
  ↓
STT
  ↓
editable transcript
  ↓
user correction
  ↓
send as order | talk
```

By the time the backend receives the request:

```text
interaction_mode = order | talk
message = finalized text
```

The backend does not need to know whether the message originated from direct
speech or from the editable Text flow.

---

# Semantic Routes

LangGraph routes by meaning rather than by UI button.

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
- record a meaningful preference

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
   Build response text
```

AI determines what should happen.

Application code performs validated mutations.

---

# Query Route

Purpose:

> Return information, reasoning, explanation, or advice without changing domain state.

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
Build response text
   │
   ▼
Save conversation history
```

The Query route is read-only with respect to domain state.

---

# Clarification Route

Purpose:

> Ask for missing information when Butler cannot safely continue.

Example:

```text
User:
"Move my meeting later."

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
Generate concise question text
       │
       ▼
Persist conversation context
       │
       ▼
Return clarification text
```

The next request is interpreted with previous conversation history.

No PlannerSession-style table is required.

---

# ButlerState Mental Model

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
    ├── changed_entities
    └── requires_follow_up
```

This is a conceptual grouping, not a requirement for nested Python models.

---

# Request Fields

## `user_id`

Derived from authenticated identity.

Never trust arbitrary request payload `user_id`.

## `message`

The finalized text Butler should interpret.

Its client origin may have been:

```text
direct speech
reviewed/edited Text input
```

That distinction is not needed for backend reasoning.

## `interaction_mode`

Allowed initial values:

```text
order
talk
```

It describes the user's interaction expectation.

It must not be treated as semantic intent.

---

# Context Fields

## `conversation_history`

Provides continuity for:

- clarification responses
- references
- follow-up requests
- recent conversational context

Load bounded relevant history rather than everything.

## `user_context`

Durable or temporary information relevant to reasoning.

## `daily_plan`

The relevant day's plan.

## `relevant_events`

Only the subset of events directly relevant to the request when practical.

---

# Understanding Fields

## `intent`

Initial semantic values:

```text
command
query
clarify
```

## `requested_action`

Examples:

```text
create_event
update_event
skip_event
delay_event
update_user_context
```

## `required_information`

Information required to safely complete the current task.

## `missing_information`

Required information that could not be resolved from the current message,
conversation history, User Context, Daily Plan, or relevant events.

---

# Execution Fields

## `proposed_changes`

Structured domain changes suggested by reasoning before application.

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

These must be validated before persistence.

## `applied_changes`

Changes successfully applied by deterministic application logic.

## `execution_status`

Possible initial strings:

```text
pending
completed
clarifying
failed
```

---

# Result Fields

## `response`

The canonical textual Butler response.

Examples:

```text
"Done. Your meeting is now at 4 PM."

"You have a meeting at 3 PM and groceries after work."

"What time would you like me to move it to?"
```

The backend returns text.

The client decides whether to:

- show it in an overlay
- read it aloud
- present it in call-style mode
- let the user simply read and dismiss it

## `changed_entities`

Identifies domain entities changed during execution.

Useful for sync, refresh, confirmation, and tracing.

## `requires_follow_up`

```text
true  → clarification/user input still required
false → interaction complete
```

---

# Response Delivery Boundary

The backend no longer owns a speech/text delivery mode for ordinary Butler
responses.

The separation is:

```text
LangGraph
→ semantic result

Application
→ response text + semantic metadata

Client
→ visual overlay / Speak / Call-style / dismiss
```

Therefore, do not return backend concepts such as:

```text
spoken
text
call
```

as authoritative delivery modes for ordinary Butler responses.

The response payload should remain presentation-neutral.

---

# Persistence and Response Are Independent

Examples:

```text
"Move my meeting to 4 PM."

Domain persistence: yes
Conversation persistence: yes
Response text: yes
```

```text
"What do I have after lunch?"

Domain persistence: no
Conversation persistence: yes
Response text: yes
```

```text
"Remember that I prefer late meetings."

Domain persistence: yes
Conversation persistence: yes
Response text: yes
```

Do not equate response generation with read-only behavior.

---

# Domain Persistence

Domain persistence changes product state.

Examples:

- Daily Plan
- Daily Event
- User Context
- device or sync state

Mutations are performed by deterministic application code after validation.

---

# Conversation Persistence

Conversation history may be written for both Query and Command routes.

```text
user message
    ↓
conversation_history
    ↓
Butler response text
    ↓
conversation_history
```

Conversation persistence is separate from domain mutation.

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
Validate interaction_mode = order | talk
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
   │    │    └── build clarification text
   │    │
   │    └── reason over loaded context
   │         └── build answer text
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
       return text result
```

---

# Example Request and Result

Request:

```json
{
  "interaction_mode": "order",
  "message": "Move my project review to 4 PM."
}
```

Result:

```json
{
  "response": "Done. I moved your project review to 4 PM.",
  "changed_entities": [
    {
      "type": "daily_event",
      "id": "..."
    }
  ],
  "requires_follow_up": false
}
```

The backend does not specify whether that response should be spoken.

---

# Direct Event Updates

Direct client CRUD should bypass AI.

```text
Client edit
   ↓
Sync API
   ↓
Authenticate
   ↓
Validate version
   ↓
Apply deterministic update
   ↓
Persist
   ↓
Return authoritative entity
```

---

# Daily Plan Lifecycle

```text
Night
  ↓
Prepare tomorrow plan
  ↓
Create Daily Events
  ↓
Generate Morning Brief content
  ↓
Persist

Day
  ↓
Events change as reality changes
  ↓
Client syncs updates

Night
  ↓
Read final day state
  ↓
Generate Good Night Summary content
  ↓
Persist
```

The backend generates content.

The client controls how that content is presented or spoken.

---

# Morning Brief

Morning Brief generation:

```text
Tomorrow Daily Plan
      ↓
Relevant User Context
      ↓
Generate brief text/content
      ↓
Persist as Daily Event content
      ↓
Client sync
```

The backend does not need to execute speech.

## Implemented Tomorrow-Planning Workflow

The initial vertical slice executes this focused workflow:

```text
authenticated user + target date
        ↓
active date-bounded User Context
        +
protected non-planner events for the target date
        ↓
structured AI day proposal
        ↓
deterministic field and time validation
        ↓
final protected + proposed event sequence
        ↓
structured Morning Brief generation
        ↓
one transaction locks/upserts DailyPlan and planner-keyed DailyEvents
```

No write occurs until both AI calls and deterministic validation succeed. A
database failure rolls back the complete replacement. Regeneration locks the
DailyPlan row, updates stable planner keys, removes only obsolete
`origin=planner` rows, and never deletes non-planner commitments. Repeated runs
therefore produce one DailyPlan, one Morning Brief, and no duplicate generated
event keys. A Butler-mediated user update or skip clears the planner key and
changes the event origin to `user`, so a later planning run treats that explicit
decision as protected input. An identical regeneration preserves generated event
IDs and versions; changed generated fields increment their versions.

Application code owns the target date, event date, supported event types,
ordering, Morning Brief event type, and proactive speech flag. Daytime proposed
events are normalized to `speak_aloud=false`; the persisted Morning Brief is
`speak_aloud=true` and uses its own scheduled start time.

The configured Butler timezone currently applies to every user. Event dates and
times are delivered as local values and Android interprets them in the device
timezone. **The deployment requires the device timezone to equal
`BUTLER_DEFAULT_TIMEZONE` for every user/device.** The default server UTC value
must be changed when devices use another zone. Today/tomorrow, evening
preparation and alarm conversion all depend on this constraint. Per-user
timezone storage and cross-zone travel are deferred.

---

# Good Night Summary

Good Night Summary generation:

```text
Final Daily Plan state
      ↓
Actual event outcomes
      ↓
Relevant context
      ↓
Generate summary text/content
      ↓
Persist as Daily Event content
      ↓
Client sync
```

Again, playback is a client concern.

## Implemented Evening Preparation

At the configured preparation time (initially 22:30), or through authenticated
`POST /api/planning/evening-prepare`, the backend serializes work for the user,
loads today's actual event states, generates and validates Good Night Summary
content, prepares tomorrow through the existing protected-event planner, and
persists today's summary at 22:45. The workflow then emits a lightweight
`daily_plan_changed` FCM data message after each canonical transaction commits.
A user-owned summary is not overwritten. `DailyPlanChanges` provides the same
post-commit publication boundary for Butler event actions, explicit preparation
and direct sync, even if a later workflow step fails. Push failure never rolls
back canonical state. Each publication contains only the hint; clients fetch
canonical state through authenticated sync.

---

# Synchronization

```text
Client change
   ↓
Sync request
   ↓
Authenticate
   ↓
Validate entity/version
   ↓
Reconcile
   ↓
Persist
   ↓
Return authoritative state
```

Use simple optimistic concurrency.

No event-sourcing architecture is required.

The implemented client mutation batch supports create, edit, complete, skip,
delay, cancel, and delete. Each operation is idempotent by operation ID. Matching
base versions apply and increment the event version; mismatches return the
current canonical event with `status=conflict`. Deletion uses the existing
server tombstone and disappears from subsequent day snapshots.

---

# Guiding Workflow

The backend should answer four questions cleanly:

```text
Who is the user?
What does the request mean?
What state should change?
What text should Butler return?
```

The client answers the presentation question:

```text
How should the user receive that text?
```

That keeps Butler semantics and Android presentation cleanly separated.
