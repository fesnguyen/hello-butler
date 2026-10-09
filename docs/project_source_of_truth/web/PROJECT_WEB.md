# PROJECT_WEB — Web Subsystem

**Status:** Source of Truth · **Authority:** [PROJECT.md](../PROJECT.md) → [ENGINEERING.md](../ENGINEERING.md) → this document → feature documents.

## Scope

The web subsystem contains the public Showcase and the planned Admin experience. Each web application's trusted server layer owns its database access; browser code must not connect directly to PostgreSQL or contain database credentials.

## Architecture and boundaries

Showcase is a React/Vite/Tailwind frontend plus a small Node/TypeScript HTTP server using PostgreSQL. Its database is separate from the Hello Butler functional database, even when both share one PostgreSQL instance. Showcase does not proxy through the Butler FastAPI backend. The Admin experience is planned; do not treat its APIs or implementation as delivered.

## Feature index

- [showcase/SHOWCASE.md](showcase/SHOWCASE.md) — authoritative Showcase data model, HTTP API, setup, schema initialization, and verification.

## Evolution rule

Update this parent when web-wide responsibilities, boundaries, or technology choices change. Keep feature-specific contracts and implementation detail in the corresponding child document; do not invent new child documents for unimplemented features.
