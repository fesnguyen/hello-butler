# ARCHITECTURE.md

# Personal Butler — System Architecture

**Version:** 1.0  
**Status:** Draft  
**Authority:** Derived from `PROJECT.md`

---

## 1. Purpose

Defines the high-level technical structure of Personal Butler.

`PROJECT.md` is the source of truth for product behavior and workflows.

This document defines stable technical boundaries and architectural decisions.

Detailed implementation belongs to the relevant `backend/` and `mobile/`
documents.

---

## 2. System Architecture

Personal Butler consists of two primary systems:

```text
                 USER
                   │
                   ▼
                MOBILE
                   │
              Butler API
                   │
                   ▼
                BACKEND
                   │
          ┌────────┼────────┐
          ▼        ▼        ▼
       Context  Planning    AI
          │        │
          └────┬───┘
               ▼
         Daily Events
               │
               ▼
        Synchronization
               │
               ▼
             MOBILE
               │
               ▼
        Local Execution
```

### Backend

Owns:

- Butler reasoning
- User Context
- Daily Planning
- Daily Events
- Authoritative server state
- AI integration
- Synchronization

### Mobile

Owns:

- User interface
- Local state and database
- Voice and speech capabilities
- Notifications and alarms
- Local Daily Event execution
- Offline execution
- Synchronization

---

## 3. Backend Architecture

The backend follows a layered architecture:

```text
Presentation
      ↓
Application
      ↓
Domain
      ↓
Infrastructure
```

### Presentation

External communication:

- API
- Authentication
- Validation
- Serialization

### Application

Coordinates use cases, transactions, and domain operations.

### Domain

Contains business rules and core domain concepts.

### Infrastructure

Implements external concerns:

- Database
- AI providers
- Cache
- Background processing
- External services

---

## 4. Key Boundaries

### Mobile ↔ Backend

Communication happens through explicit API and synchronization contracts.

Mobile does not depend on backend implementation details.

### Application ↔ Domain

Application coordinates operations.

Domain owns business rules.

### Domain ↔ Infrastructure

Domain depends on abstractions.

Infrastructure provides implementations.

### Domain ↔ AI

Business logic accesses AI through an abstraction rather than a specific
provider or SDK.

### Backend ↔ Database

Backend persistence is accessed through repository/persistence abstractions.

---

## 5. Dependency Rules

- Domain must not depend on frameworks.
- Domain must not depend on infrastructure.
- Business logic must not directly access the database.
- Business logic must not directly access environment variables.
- Business logic must not directly depend on an AI provider.
- Mobile must not depend on backend implementation details.
- Infrastructure implements interfaces defined by inner layers.

```text
Presentation
     ↓
Application
     ↓
Domain
     ↑
Infrastructure
```

---

## 6. Primary API Boundary

The user interacts with Butler through one primary conversational API.

The API should not expose separate user-facing endpoints for product
concepts such as schedules, routines, or reminders.

Internal components may still use multiple services, use cases, and tools.

---

## 7. Data Ownership

### Backend

Authoritative for:

- User Context
- Planning state
- Generated Daily Events
- Server-side historical/contextual data

### Mobile

Authoritative for:

- Local execution state
- Device-specific state
- Local notification scheduling
- Offline queued actions

The same information may exist in both systems, but ownership must remain
explicit.

---

## 8. Architectural Change

Update `ARCHITECTURE.md` only when the technical structure changes.

Examples:

- Moving speech recognition from mobile to backend
- Changing the backend/mobile boundary
- Changing the synchronization architecture
- Introducing or removing a major system boundary
- Changing the persistence architecture
- Changing the architectural pattern

Product behavior changes begin in `PROJECT.md`.

Implementation-detail changes belong in the relevant technical sub-document.

---

## 9. Documentation Hierarchy

```text
PROJECT.md
    │
    ▼
ARCHITECTURE.md
    │
    ├── backend/
    │   ├── FLOWS.md
    │   ├── STRUCTURE.md
    │   ├── TECH_STACK.md
    │   └── DATABASE.md
    │
    └── mobile/
        └── ...
```

`PROJECT.md` defines **what**.

`ARCHITECTURE.md` defines the high-level technical **shape**.

Technical sub-documents define implementation details.

---

## 10. Guiding Principle

Keep the architecture as simple as the product allows.

Do not create a separate service, API, module, or abstraction merely because
a product concept exists.

Introduce a technical boundary when it provides a meaningful architectural
benefit.
