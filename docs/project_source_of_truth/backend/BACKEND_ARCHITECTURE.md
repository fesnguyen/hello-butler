# Backend Architecture

**Version:** 1.2  
**Status:** Initial  
**Authority:** Derived from `PROJECT.md` and `ENGINEERING.md`

---

# Purpose

This document defines how the Butler backend is built.

It owns backend-specific decisions such as project structure, architectural
pattern, technology stack, major components, dependency boundaries, API
boundaries, persistence, authentication, AI integration, and synchronization
architecture.

Global engineering rules belong in `ENGINEERING.md` and are not repeated here.

---

# Backend Responsibilities

The backend owns:

- Butler reasoning
- User Context
- conversation history
- Daily Planning
- Daily Events
- Morning Brief generation
- Good Night Summary generation
- dynamic replanning
- authoritative server state
- authentication
- AI provider integration
- synchronization with clients

The client remains responsible for local execution, local notifications, speech,
offline state, queued synchronization, and deciding how text responses are
presented or spoken.

---

# Technology Stack

```text
Runtime / Tooling
├── Python 3.12+
├── uv
├── Ruff
└── Pyright

Backend
├── FastAPI
├── Pydantic v2
├── SQLAlchemy 2.x Async
└── Alembic

Database
└── PostgreSQL 16+

AI
├── LangGraph
└── OpenAI Responses API

Infrastructure
├── Docker
└── Docker Compose
```

Do not add infrastructure until an actual requirement justifies it.

---

# Architectural Pattern

The backend uses a layered architecture:

```text
API
 ↓
Application
 ↓
Domain
 ↑
Infrastructure
```

## API

Owns HTTP routing, authentication extraction, request validation, and response
serialization.

## Application

Owns use cases, workflow coordination, transaction boundaries, and orchestration
between domain and infrastructure.

## Domain

Owns core entities, business rules, planning concepts, state transitions, and
repository/provider abstractions where needed.

## Infrastructure

Owns PostgreSQL persistence, SQLAlchemy models, repository implementations,
AI provider implementations, and external integrations.

---

# Project Structure

Initial structure:

```text
hello-butler/
├── backend/
│   ├── alembic/
│   ├── app/
│   │   ├── api/
│   │   │   ├── auth.py
│   │   │   ├── butler.py
│   │   │   └── sync.py
│   │   ├── application/
│   │   │   ├── auth/
│   │   │   ├── butler/
│   │   │   ├── planning/
│   │   │   └── sync/
│   │   ├── domain/
│   │   │   ├── user.py
│   │   │   ├── user_context.py
│   │   │   ├── conversation.py
│   │   │   ├── daily_plan.py
│   │   │   └── daily_event.py
│   │   ├── infrastructure/
│   │   │   ├── db/
│   │   │   ├── repositories/
│   │   │   └── ai/
│   │   ├── core/
│   │   │   ├── config.py
│   │   │   ├── security.py
│   │   │   └── database.py
│   │   └── main.py
│   ├── Dockerfile
│   ├── pyproject.toml
│   ├── uv.lock
│   └── alembic.ini
├── client/
├── docs/
└── compose.yaml
```

The structure may evolve as implementation pressure appears.

Do not create additional layers or modules without a concrete reason.

---

# Development and Runtime

Local development separates application execution from infrastructure.

```text
Host
├── Backend
│   └── uv → FastAPI
└── Docker Compose
    └── PostgreSQL
```

During normal backend development, run FastAPI directly through `uv`.

Docker Compose runs infrastructure dependencies such as PostgreSQL.

Database migrations are executed explicitly with Alembic and are not coupled to
application startup.

Typical development flow:

```text
docker compose up -d
uv sync
uv run alembic upgrade head
uv run fastapi dev app/main.py
```

---

# Primary API Boundaries

```text
/api/auth/...
/api/butler/talk
/api/sync/...
```

`/api/butler/talk` is the single Butler conversational endpoint.

The backend recognizes only two interaction expectations:

```text
order
talk
```

`Text` is not a backend interaction mode.

On the client, Text is an input-preparation path:

```text
speech
  ↓
STT
  ↓
editable text
  ↓
send as order | talk
```

By the time a request reaches the backend, it has a final message and one of the
two interaction modes.

Example:

```json
{
  "interaction_mode": "order",
  "message": "Move my meeting to 4 PM."
}
```

---

# Response Contract

The backend returns Butler responses as **textual semantic content**.

The backend decides:

```text
What happened?
What should Butler say?
```

The client decides:

```text
How should the user receive it?
```

The backend should not decide whether a normal Butler response is:

- spoken aloud
- played in call-style presentation
- shown only as text
- acknowledged silently

Those are client presentation decisions based on user preference and current UI
state.

Conceptually:

```text
Backend
→ response text + semantic metadata

Client
→ text overlay
→ optional Speak
→ optional Call-style receive
→ dismiss / acknowledge
```

For ordinary Butler responses, text is the canonical delivery payload.

Morning Brief and Good Night Summary are still generated as content by the
backend, while automatic speech behavior remains a client-side presentation
choice/configuration.

---

# Authentication

Authentication is a small production system, not a temporary development stub.

The supported user-facing methods are:

```text
Email + password
├── Register
└── Login

Google
└── Sign in with Google
```

The backend owns credential verification, token issuance and refresh, session
revocation, and mapping external/login identities to the internal `User`.

Authentication identity is separate from the Butler `User` entity:

```text
User
  │
  └── AuthIdentity[]
      ├── password
      └── google
```

This keeps Butler-owned user data independent from the mechanism used to sign in.
An authentication identity stores only provider-specific information required to
verify or resolve that identity.

## Password Authentication

Passwords are never stored directly.

Use Argon2id for password hashing with a maintained library and safe library
defaults. Password hashes belong to the password authentication identity, not to
Butler domain data.

Registration requires a normalized email and password. Login compares the
submitted password against the stored hash.

## Google Authentication

The client obtains a Google credential through the platform-supported Google
sign-in flow and sends the resulting Google ID token to the backend.

The backend verifies the token with Google, including issuer, audience,
signature, expiry, and provider subject. The stable Google `sub` is the external
identity key; email alone is not an identity key.

Do not automatically link an existing password identity to a Google identity
solely because the email strings match. Account linking, if introduced, must be
an explicit authenticated action.

## Access and Refresh Tokens

Successful authentication establishes a Butler session:

```text
Authentication
      ↓
short-lived access token
      +
rotating refresh token
```

Access tokens are signed JWTs containing only the claims required to authenticate
a request. They are intentionally short-lived.

Refresh tokens are high-entropy opaque secrets. Store only a cryptographic hash
of each refresh token in PostgreSQL together with its owning user/session,
expiry, revocation state, and rotation metadata.

A successful refresh rotates the refresh token: the presented token is consumed
and replaced. Logout revokes the current refresh session. Reuse of an already
rotated/revoked refresh token invalidates that refresh session.

Exact lifetimes are configuration, with an initial target of roughly 15 minutes
for access tokens and 30 days for refresh sessions.

## Authenticated Request Boundary

Protected endpoints resolve the internal user from the verified access token.

```text
Authorization: Bearer <access token>
              ↓
verify signature + expiry
              ↓
resolve authenticated user
              ↓
execute request within that user's data
```

Every authenticated request operates within the authenticated user's own data.
Never trust a client-provided `user_id` as proof of identity.

No roles, organizations, RBAC, or general permission framework are required.

Authentication secrets and provider configuration come from environment-backed
settings and are never committed to source control.

---

# Daily Plan and Daily Event Model

`DailyPlan` represents one day.

```text
DailyPlan
└── DailyEvent[]
```

`DailyEvent` remains flexible enough to support different purposes.

Examples:

```text
Morning Brief
├── long content
└── speak_aloud = true

Meeting Reminder
├── scheduled time
├── notification behavior
└── speak_aloud = false

Good Night Summary
├── long content
└── speak_aloud = true
```

A single flexible `DailyEvent` model should support these purposes.

Do not create separate domain modules or database tables for each event type
without a concrete requirement.

Event categories remain string values.

---

# AI Architecture

Application and domain logic do not call OpenAI directly.

```text
Butler / Planner
      ↓
AI abstraction
      ↑
OpenAI provider
```

The AI layer may support intent interpretation, planning, contextual answering,
Morning Brief generation, Good Night Summary generation, and structured decision
support.

Deterministic operations remain normal code, including authentication,
persistence, version checking, direct event CRUD, and sync conflict detection.

AI output must be validated before important state changes are applied.

---

# Persistence Architecture

PostgreSQL is the authoritative backend database.

SQLAlchemy async is used for runtime access.

Alembic manages schema migration independently from application startup.

Persistence is accessed through repositories or focused abstractions.

The database stores users, authentication identities and refresh sessions, User
Context, conversation history, Daily Plans, Daily Events, devices, and
synchronization metadata.

Types such as event type, context type, interaction mode, status, role, provider,
and platform are stored as strings.

`interaction_mode` initially allows only:

```text
order
talk
```

Do not create lookup tables solely to represent fixed string categories unless a
real relational requirement appears later.

---

# Synchronization Architecture

The client is local-first.

```text
Client action
   ↓
Local DB
   ↓
Sync queue
   ↓
Backend sync API
   ↓
Authenticate
   ↓
Reconcile
   ↓
PostgreSQL
   ↓
Return server state
```

For Daily Events, the initial optimistic concurrency mechanism is:

```text
version INTEGER
updated_at
deleted_at nullable
```

No event-sourcing system is required.

---

# Data Ownership

Backend is authoritative for authenticated identity, User Context, server
conversation history, Daily Plans, Daily Events after synchronization, generated
Butler content, and server-side versions.

Client owns immediate local state before synchronization, device-specific
execution state, notification/alarm scheduling, local queues, temporary UI state,
and response presentation behavior.

---

# Background Work

Backend background work may be used for:

- nightly planning
- preparing tomorrow's Daily Plan
- preparing Morning Brief content
- preparing Good Night Summary content
- delayed maintenance tasks

Do not introduce a separate worker platform until the workload requires it.

## Initial Morning Brief Vertical Slice

The first implemented planning trigger is the authenticated
`POST /api/planning/prepare` use case. It defaults to tomorrow in the configured
Butler timezone; nightly invocation is intentionally not yet attached to a
separate worker platform. The same application service can be called by a future
in-process or external scheduler without changing planning behavior.

Prepared days are delivered through the focused authenticated read contract:

```text
GET /api/sync/daily-plan/{date}
```

This is a server-to-client bootstrap snapshot, not the complete future
bidirectional synchronization protocol.

`DailyEvent.origin` is initially `user` or `planner`. Planner-owned rows also
carry a stable nullable `planner_key`; `(user_id, event_date, planner_key)` is
unique. Existing rows migrate to `origin=user`, making them protected inputs to
planning rather than replaceable generated output.

---

# Guiding Architecture

```text
                    CLIENT
                       │
        ┌──────────────┼──────────────┐
        │              │              │
       Auth          Butler          Sync
        │              │              │
        └──────────────┼──────────────┘
                       ▼
                      API
                       │
                       ▼
                  APPLICATION
                       │
              ┌────────┼────────┐
              ▼        ▼        ▼
          Daily Plan  Butler  Authentication
              │        │
              └────┬───┘
                   ▼
                 DOMAIN
                   │
              Abstractions
                ▲       ▲
                │       │
         PostgreSQL     AI
```

Keep the backend small enough that the important code path remains easy to
follow.

Add architectural complexity only when the product has earned it.
