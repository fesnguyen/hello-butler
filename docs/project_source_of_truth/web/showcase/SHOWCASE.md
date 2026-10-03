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

V1 reads persisted content through the aggregate API. Application names, descriptions, source links, media, versions, and downloads are never maintained in React.

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

V1 exposes `GET /api/showcase/applications`, without authentication. Each application includes `id`, `name`, `description`, nullable `github_url`, ordered `media[]` (`id`, `url`, `display_order`), and nullable `latest_release` (`version`, `download_url`, `published_at`). An empty collection returns `[]`.

Media uses ascending `display_order` then ID. The latest release has the greatest `published_at` at or before the current UTC time; ID breaks ties deterministically. The service uses three bulk queries for a nonempty collection, independent of application count. Links in the API/seed contract use HTTP(S) URLs.

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

## V1 setup and operation

Showcase uses `SHOWCASE_DATABASE_URL`, its own SQLAlchemy metadata/session, and `backend/alembic_showcase.ini`. Butler continues using `DATABASE_URL` and `backend/alembic.ini`; neither migration history contains the other's tables. Settings reject equal database names. There are exactly three Showcase content tables plus Alembic's technical version table. Migrations never run at API startup.

### Development

From the repository root, copy `.env.example` to `.env.dev`, replace the password/JWT placeholders, and keep `POSTGRES_DB=hello_butler_dev` and `SHOWCASE_DB=showcase_dev`. URL-encode special characters in database URL passwords. Existing Butler/Firebase/OpenAI settings continue to apply as documented in [Deployment](../../../DEPLOYMENT.md).

```bash
docker compose -p hello-butler-dev --env-file .env.dev up -d postgres
# Required for an existing volume; also safe to repeat on a fresh volume:
docker compose -p hello-butler-dev --env-file .env.dev exec postgres sh /docker-entrypoint-initdb.d/init-showcase.sh
cd backend
uv sync --frozen
uv run --env-file ../.env.dev alembic upgrade head
uv run --env-file ../.env.dev alembic -c alembic_showcase.ini upgrade head
uv run --env-file ../.env.dev python -m app.infrastructure.db.seed_showcase showcase.seed.example.json
uv run --env-file ../.env.dev fastapi dev app/main.py --port 8001
```

The mounted PostgreSQL initialization script creates the Showcase database on fresh volumes. The explicit `exec` command handles existing volumes without resetting data. It is idempotent and uses the existing PostgreSQL role. Re-running `up -d postgres` applies the new mount/environment to existing containers. Keep the existing dev/prod Compose project names and volumes; do not delete volumes to initialize Showcase.

In another terminal, from the repository root:

```bash
cd web/showcase
# Node 24+; use the pinned packageManager version (pnpm 12.8.1).
pnpm install --frozen-lockfile
cp .env.example .env.local
pnpm dev
```

The default Vite URL is `http://localhost:5173`. `/api` proxies to `http://localhost:8001`; `SHOWCASE_DEV_API_TARGET` can change that development target. The page shows an intentional typographic cover when no media exists, omits absent source/download links, and handles loading, empty collections, and failed requests with retry.

### Production configuration and migrations

Keep `POSTGRES_DB=hello_butler` and `SHOWCASE_DB=showcase` in `.env.production`. Backend URLs use the Compose hostname `postgres:5432`, ending in `/hello_butler` and `/showcase` respectively. The database names differ; the server and existing PostgreSQL role are shared.

From the repository root:

```bash
docker compose -p hello-butler-prod --env-file .env.production up -d postgres
docker compose -p hello-butler-prod --env-file .env.production exec postgres sh /docker-entrypoint-initdb.d/init-showcase.sh
docker compose -p hello-butler-prod --env-file .env.production build backend
docker compose -p hello-butler-prod --env-file .env.production run --rm backend uv run --no-sync alembic -c alembic_showcase.ini upgrade head
```

Butler's existing migration command remains required independently. `alembic -c alembic_showcase.ini current` and `check` inspect the Showcase history/schema. No deployment or GitHub Actions workflow is included in V1.

`VITE_API_BASE_URL` is an optional **build-time** public API origin (for example `https://api.example.com`), without the `/api/showcase` suffix. Empty means same-origin. For same-origin production hosting, the eventual web server must route `/api` to FastAPI; Vite's development proxy is not part of `dist`. For separate origins, configure backend `SHOWCASE_ALLOWED_ORIGINS` as a JSON list of exact frontend origins, e.g. `["https://showcase.example.com"]`. CORS allows public GETs without credentials. Database URLs/credentials belong only in backend environment files, never `VITE_*` variables. Rebuild when the API base URL changes.

### Initial content and future releases

The explicit seed command imports `backend/showcase.seed.example.json`. It contains Hello Butler's stable application UUID, description, public repository link, and empty media/releases. No production artifact URL is invented. Copy/edit the manifest to supply actual media/release metadata. Nested media require `id`, `url`, `display_order`; releases require `id`, `version`, `download_url`, `published_at` (timezone-aware ISO 8601). Each child belongs to its enclosing application.

The importer commits atomically, validates HTTP(S) links, and upserts by UUID. Preserve IDs when updating records; repeated imports do not create duplicates. Missing manifest children are not deleted, so adding a release preserves older releases. It is an operator bootstrap tool, not a public write API. Future Admin/release automation can write the same tables without changing the public API/page.

### Deterministic checks/build

From `backend`, with the appropriate backend environment loaded:

```bash
uv run --env-file ../.env.dev python -m unittest discover -s tests -v
uv run ruff check .
uv run pyright
uv run --env-file ../.env.dev alembic -c alembic_showcase.ini check
```

From `web/showcase`:

```bash
pnpm install --frozen-lockfile
pnpm test
pnpm typecheck
pnpm lint
pnpm format:check
pnpm build
```

The build produces `web/showcase/dist`. React 19, Vite 8, TypeScript 6 (compatible with the ESLint parser), Tailwind 4, and TanStack Query 5 are pinned in `package.json`/`pnpm-lock.yaml`. Only esbuild's required build script is approved in `pnpm-workspace.yaml`. Native cards/links need no shadcn dependency in V1. These commands are non-interactive and ready for future CI; deployment/release publication remain separate follow-up work.
