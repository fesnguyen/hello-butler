# Backend Architecture

**Version:** 1.0  
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
offline state, and queued synchronization.

---

# Technology Stack

```text
Runtime / Tooling
├── Python 3.12+
├── uv                    Dependency and environment management
├── Ruff                  Linting and formatting
└── Pyright               Static type checking

Backend
├── FastAPI               HTTP API
├── Pydantic v2           Validation and API contracts
├── SQLAlchemy 2.x Async  ORM and persistence
└── Alembic               Database migrations

Database
└── PostgreSQL 16+        Primary database

AI
├── LangGraph             Multi-step Butler workflows where justified
└── OpenAI Responses API  Primary AI provider

Infrastructure
├── Docker                Reproducible runtime and deployment
└── Docker Compose        Local infrastructure
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

`pyproject.toml` defines the Python project and dependencies.

`uv.lock` locks exact dependency versions and is committed to source control.

`compose.yaml` lives at repository root because it manages project-level
infrastructure rather than backend source code.

The structure may evolve as actual implementation pressure appears.

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

During normal backend development, run FastAPI directly through `uv` for fast
iteration.

Docker Compose runs infrastructure dependencies such as PostgreSQL.

The backend Docker image provides a reproducible runtime for deployment and
containerized execution.

Database migrations are executed explicitly with Alembic and are not coupled to
application startup.

Typical development flow:

```text
docker compose up -d
        ↓
uv sync
        ↓
uv run alembic upgrade head
        ↓
uv run fastapi dev app/main.py
```

Docker is a deployment and infrastructure boundary, not an application
architecture layer.

---

# Primary API Boundaries

The backend exposes separate technical boundaries while preserving one Butler
from the user's perspective.

```text
/api/auth/...        Authentication operations
/api/butler/talk     Main Butler interaction
/api/sync/...        Client/server synchronization
```

`/api/butler/talk` handles Order Butler, Talk to Butler, and Text Butler.

The interaction mode is part of the request contract rather than a separate
service.

Example:

```json
{
  "interaction_mode": "order",
  "message": "Move my meeting to 4 PM."
}
```

---

# Authentication

Authentication establishes the identity of the current user.

The backend does not currently use roles, sub-owners, or a general permission
system.

Every authenticated request operates within the authenticated user's own data.

Never trust a client-provided `user_id` as proof of identity.

The authenticated identity is used when reading or modifying User Context,
conversation history, Daily Plans, Daily Events, device state, and sync state.

```text
Credentials / provider identity
            ↓
       Authentication
            ↓
     Authenticated User
            ↓
       User-owned data
```

---

# Daily Plan and Daily Event Model

`DailyPlan` represents one day.

It contains the collection of `DailyEvent` entities expected or recorded for that
day.

```text
DailyPlan
└── DailyEvent[]
```

`DailyEvent` remains a separate domain entity because events can serve different
purposes and require different configuration.

```text
Morning Brief
├── long content
└── speak_aloud = true

Meeting Reminder
├── scheduled time
├── notification behavior
└── speak_aloud = false

Grocery Reminder
├── optional time
└── configurable reminder behavior

Good Night Summary
├── long content
└── speak_aloud = true
```

A single flexible `DailyEvent` model should support these purposes.

Do not create separate domain modules or database tables for each event type
unless a concrete requirement later justifies it.

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

Deterministic operations should remain normal code, including authentication,
persistence, version checking, direct event CRUD, and sync conflict detection.

AI output must be validated before important state changes are applied.

---

# Persistence Architecture

PostgreSQL is the authoritative backend database.

SQLAlchemy async is used for runtime database access.

Alembic manages schema migration independently from application startup.

Persistence is accessed through repositories or focused persistence abstractions.

The database stores users, authentication data, User Context, conversation
history, Daily Plans, Daily Events, devices, and synchronization metadata.

Types such as event type, context type, interaction mode, status, role, and
platform are stored as strings.

Do not create lookup tables solely to represent fixed string categories unless a
real relational requirement appears later.

`DailyPlan` and `DailyEvent` remain separate relational entities:

```text
daily_plans
    1
    │
    └──── *
       daily_events
```

This allows individual events to be edited, completed, delayed, synchronized,
versioned, or deleted without replacing the entire day.

---

# Synchronization Architecture

The client is local-first.

A client change should not wait for the backend before updating the UI.

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

Syncable entities require enough metadata to detect stale updates.

For Daily Events, the initial mechanism is:

```text
version INTEGER
updated_at
deleted_at nullable
```

A simple integer version is sufficient for initial optimistic concurrency.

No event-sourcing system is required.

---

# Data Ownership

Backend is authoritative for authenticated user identity, User Context, server
conversation history, Daily Plans, Daily Events after synchronization, generated
Butler content, and server-side versions.

Client owns local execution state before synchronization, device-specific state,
local notification scheduling, local queues, and temporary UI state.

When the same entity exists on both sides, synchronization rules determine how
the states converge.

---

# Background Work

Backend background work may be used for nightly planning, preparing tomorrow's
Daily Plan, preparing Morning Brief content, preparing Good Night Summary
content, and delayed maintenance tasks.

Do not introduce a separate worker platform until the workload requires it.

The first implementation should prefer the simplest reliable scheduling approach.

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
