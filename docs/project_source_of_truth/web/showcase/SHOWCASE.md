# Showcase V1

**Status:** Source of Truth · **Parent:** [PROJECT_WEB.md](../PROJECT_WEB.md)

## Ownership

One PostgreSQL instance hosts independent databases: `hello_butler_dev` and `showcase_dev` in development; `hello_butler` and `showcase` in production.

- Hello Butler: Android → FastAPI → Butler database; existing Alembic remains unchanged.
- Showcase: browser → Showcase Node/TypeScript server → Showcase database. `web/showcase` owns React/Vite/Tailwind UI, HTTP server and PostgreSQL access. It never proxies through Butler.
- Admin (future): ecosystem management through its own trusted server-side layer, potentially accessing multiple application databases. No Admin implementation or APIs in V1.

Credentials belong exclusively to server environments. Browser code never connects to PostgreSQL. Node's HTTP server and `pg` keep the three-table application small without adding a full-stack framework.

## Data and public API

Exactly three essential tables:

| Table | Columns |
| --- | --- |
| applications | id UUID PK, name, description, nullable github_url |
| media | id UUID PK, application_id FK, url, display_order |
| releases | id UUID PK, application_id FK, version, download_url, published_at timestamptz |

Foreign keys cascade child deletion. Media order is `display_order`, then ID. Latest published release is greatest `published_at <= now()`, then ID. `GET /api/applications` returns applications with ordered `media[]` and nullable `latest_release`, using one aggregate query (no N+1). Empty collections return `[]`. Public endpoints are read-only; database errors return a generic 503.

Preserve the responsive V1 cards, loading/retry/empty states and absent media/source/release states. Application content comes from PostgreSQL, never React constants. No invented APK links.

## Setup

Copy root `.env.example` to `.env.dev`, replacing secrets. Butler uses `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DATABASE_URL`. Showcase uses distinct `SHOWCASE_DB`, `SHOWCASE_USER`, `SHOWCASE_PASSWORD` plus `SHOWCASE_HOST`/`SHOWCASE_PORT` for PostgreSQL and `SHOWCASE_HTTP_HOST`/`SHOWCASE_HTTP_PORT` for HTTP. No secret uses `VITE_*`.

From repository root:

```bash
docker compose -p hello-butler-dev --env-file .env.dev up -d postgres
docker compose -p hello-butler-dev --env-file .env.dev exec postgres sh /docker-entrypoint-initdb.d/init-showcase.sh
cd web/showcase
pnpm install --frozen-lockfile
pnpm db:init
pnpm db:seed
pnpm dev:server
```

Another terminal in `web/showcase`: `pnpm dev`. Open `http://localhost:5173`. Vite proxies `/api` to Showcase at `http://localhost:8001`; optional `SHOWCASE_DEV_API_TARGET` changes that target. Butler can run independently or remain stopped.

Node 24+ and the pinned pnpm version are required. Scripts load root `.env.dev`; already-set environment variables take precedence. Production supplies environment variables directly, without a local environment file.

Provisioning creates/updates the Showcase role/password, database and owner; it is repeatable on existing volumes and never deletes Butler data. Keep existing Compose project names/volumes. Never reset a volume to initialize Showcase.

`pnpm db:init` executes Showcase-owned `db/schema.sql` in a transaction, creates missing tables/indexes and never drops tables or data. It validates the target database/role differs from Butler. For a database initialized by the former FastAPI/Alembic implementation, first run the provisioning command above with `--adopt-legacy` appended. This explicitly transfers only the three legacy content tables to the Showcase role. Legacy `showcase_*` content tables are then renamed in place by schema initialization, preserving data; an existing legacy Alembic version table is left alone but unused. Initialization is not a migration framework and does not automatically alter existing columns.

`pnpm db:seed` upserts the initial Hello Butler application by stable UUID, separately from schema initialization. It does not remove media/releases or invent them. Insert actual media/releases as data when available.

Butler's existing `alembic upgrade head` remains independent and unchanged.

## Production and checks

Set production database names and `SHOWCASE_HOST=postgres`/`SHOWCASE_PORT=5432` when running on the Compose network; use localhost:5433 from the host. Set `SHOWCASE_HTTP_HOST=0.0.0.0` when exposing the server from a container. The server serves built `dist/` and `/api/applications` from the same origin.

```bash
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm lint
pnpm format:check
pnpm build
pnpm db:init
pnpm start
```

Build produces frontend `dist/` and compiled server `dist-server/`. Initialization and builds are non-interactive. Tests execute the actual schema, seed and aggregate SQL in an isolated embedded PostgreSQL engine (PGlite), plus HTTP/configuration/UI checks; no user databases are used.

Future CI/CD: install → typecheck/test/build → safe schema initialization → deploy. Release automation writes `releases`; the running website reads updates without rebuilding React. Full GitHub Actions/CD and Admin remain follow-up tasks.
