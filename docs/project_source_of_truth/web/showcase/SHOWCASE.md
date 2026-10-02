# Showcase Web

## Purpose

`web/showcase/` is the public single-page website for presenting Hello Butler and future applications.

Visitors should be able to quickly:

- understand what an application does
- view screenshots/demo media
- inspect its public GitHub repository
- download the latest published release

Keep the Showcase simple, clean, responsive, and data-driven.

## Location

```text
hello-butler/
├── backend/
├── client/
└── web/
    └── showcase/
```

Showcase stays in the same monorepo but is independent from the Android client.

## Tech Stack

Use a modern lightweight frontend stack:

- React + TypeScript
- Vite
- pnpm
- Tailwind CSS v4
- shadcn/ui where reusable components help
- TanStack Query for API data

Guidelines:

- strict TypeScript
- responsive/mobile-first UI
- accessible semantic HTML
- environment-based API configuration
- reusable components without premature abstraction
- keep dependencies small

## UI

V1 is one simple page containing:

1. small site header/identity
2. short introduction
3. application cards/sections
4. application screenshots/demo media
5. application name and description
6. `GitHub` / `View Source` link
7. `Download` for the latest published release

Hello Butler is the first application.

```text
EX'S LAB                                      GitHub

Things I've built.
Applications, experiments and demos.

┌─────────────────────────────────────────────┐
│  [ App image / demo ]                       │
│                                             │
│  Hello Butler                               │
│  Personal AI Butler for daily planning.     │
│                                             │
│  [ GitHub ]       [ Download ]              │
└─────────────────────────────────────────────┘
```

Do not depend on Google Play availability. Android releases can be downloaded directly. The public GitHub repository lets visitors inspect the project and source.

Application information and media should come from data rather than being permanently hard-coded into React.

## Data Flow

Showcase is public and read-only.

```text
PostgreSQL
    ↓
Backend API
    ↓
Showcase
```

For initial UI development, local mock data is acceptable. Keep it behind a small data/API boundary so it can be replaced by backend calls without rewriting UI components.

## Database

Use the existing PostgreSQL server/container but a separate Showcase database.

```text
PostgreSQL
├── hello_butler
└── showcase
    ├── showcase_applications
    ├── showcase_media
    └── showcase_releases
```

Development follows the same separation:

```text
PostgreSQL
├── hello_butler_dev
└── showcase_dev
```

This isolates Showcase data without running another PostgreSQL instance.

### `showcase_applications`

```text
id
name
description
github_url
```

One row represents one application.

### `showcase_media`

```text
id
application_id
url
display_order
```

Stores ordered screenshots/demo images for an application. Media is data-driven so images can later be changed through Admin or directly in persisted data without rebuilding the Showcase frontend.

### `showcase_releases`

```text
id
application_id
version
download_url
published_at
```

Stores downloadable releases. The latest published release is used for the main Download action.

Keep these tables intentionally small. Add fields only when a real Showcase/Admin requirement needs them.

## Backend API Direction

The backend owns database access. Showcase should never connect directly to PostgreSQL.

The public API only needs read operations initially, conceptually:

```text
GET applications
GET application media
GET application releases/latest release
```

Exact routes and response models should follow existing backend conventions when implemented.

The UI should consume a small typed API layer rather than making fetch calls throughout components.

## GitHub CI/CD

GitHub is the source repository and CI/CD trigger.

The design should support two related pipelines.

### Showcase web changes

When `web/showcase/` changes on `main`:

```text
merge to main
    ↓
GitHub Actions
    ↓
pnpm install --frozen-lockfile
    ↓
typecheck / test / build
    ↓
deploy Showcase
```

CI should build from the committed lockfile and fail before deployment when validation/build fails.

### Application release

For a releasable application such as Android Hello Butler:

```text
release trigger/tag
    ↓
GitHub Actions
    ↓
test/build application
    ↓
build signed release artifact
    ↓
publish artifact/release
    ↓
write/update Showcase release data
    ↓
Showcase automatically displays latest release
```

The release pipeline should provide at least:

- application identity
- version
- download URL
- publication time

These map directly to `showcase_releases`.

The Showcase frontend must not contain hard-coded versions or APK URLs. CI/CD updates release data; the existing deployed Showcase reads the new data through the API.

### CI/CD rules

- workflows should be path-aware where practical
- do not deploy if build/tests fail
- use production environment configuration at deployment time
- signing keys, API tokens, database credentials, and other secrets must use GitHub Actions secrets/environments
- never commit secrets to the repository
- release publication should be repeatable and avoid creating conflicting duplicate release records

The exact deployment host and release-publication authentication can be chosen when CI/CD is implemented; the Showcase contract should not depend on one hosting provider.

## Boundaries

Showcase is responsible for:

- public presentation of applications
- screenshots/demo media
- public GitHub links
- published release information
- release downloads

Showcase does not directly modify database content or perform administrative operations.

A separate Admin web application can later manage the same application/media/release data.

## Implementation Order

Build incrementally:

```text
1. Bootstrap web/showcase
2. Build responsive single-page UI with mock data
3. Create showcase/showcase_dev database setup
4. Add the three Showcase tables and migrations
5. Add public read-only backend API
6. Replace mock data with API data
7. Deploy Showcase
8. Add GitHub Actions web deployment
9. Add application release publication to Showcase data
```

Keep each step usable and avoid adding infrastructure or fields before they are needed.
