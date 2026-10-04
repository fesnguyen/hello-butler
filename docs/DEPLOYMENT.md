# Environment

Development:

`.env.dev`

Production:

`.env.production`

Dev:
- backend runs locally
- Postgres runs in Docker
- DB → `localhost:5433`
- Compose project → `hello-butler-dev`

Production:
- backend + Postgres run in Docker
- backend DB → `postgres:5432`
- Compose project → `hello-butler-prod`

Separate Compose projects give dev/prod separate containers, networks and DB volumes.


# Run DEV postgres

From root:

`docker compose -p hello-butler-dev --env-file .env.dev up -d postgres`

Check:

`docker compose -p hello-butler-dev --env-file .env.dev ps`

Dev volume:

`hello-butler-dev_postgres_data`


# Run DEV backend

VS Code/Uvicorn loads:

`../.env.dev`

Example args:

    "args": [
        "app.main:app",
        "--host",
        "127.0.0.1",
        "--port",
        "8001",
        "--env-file",
        "../.env.dev"
    ]

Dev backend:

`http://localhost:8001`

Use 8001 so Cloudflare/public production remains isolated on 8000.


# DEV data migration

From `backend/`:

`uv run --env-file ../.env.dev alembic upgrade head`

Check:

`uv run --env-file ../.env.dev alembic current`


# Build production backend

From root:

`docker compose -p hello-butler-prod --env-file .env.production build backend`

Or build + start:

`docker compose -p hello-butler-prod --env-file .env.production up -d --build`

Rebuild needed when:
- backend source changes
- dependencies change
- Dockerfile changes

No rebuild needed for env/normal Compose config changes.


# Run production backend + postgres

`docker compose -p hello-butler-prod --env-file .env.production up -d`

Check:

`docker compose -p hello-butler-prod --env-file .env.production ps`

Logs:

`docker compose -p hello-butler-prod --env-file .env.production logs -f backend`

Stop:

`docker compose -p hello-butler-prod --env-file .env.production down`

Do NOT use `down -v` unless intentionally deleting production DB volume.

Production volume:

`hello-butler-prod_postgres_data`


# Production data migration

Update:

`docker compose -p hello-butler-prod --env-file .env.production run --rm backend uv run --no-sync alembic upgrade head`

Check:

`docker compose -p hello-butler-prod --env-file .env.production run --rm backend uv run --no-sync alembic current`


# Production config

`.env.production` contains:
- APP_ENV
- POSTGRES_DB
- POSTGRES_USER
- POSTGRES_PASSWORD
- DATABASE_URL
- JWT_SECRET
- OPENAI_API_KEY
- other production config/secrets

Production DATABASE_URL uses:

`postgres:5432`

Postgres config in `compose.yaml`:

    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}

Backend:

    env_file:
      - ./.env.production

Compose `${...}` values require:

`--env-file .env.production`


# After production env changes

Recreate backend:

`docker compose -p hello-butler-prod --env-file .env.production up -d --no-deps --force-recreate backend`

No image rebuild required for env-only changes.


# Restart policy

Backend + Postgres:

`restart: unless-stopped`

Docker restarts containers after crash/Docker restart unless manually stopped.


# Secrets

Never commit `.env.dev` or `.env.production`.

`.gitignore`:

`.env`
`.env.*`
`!.env.example`

Check:

`git check-ignore .env.production`

Do not paste `docker compose config` output publicly — it expands secrets.


# Cloudflare Tunnel

Production:

`Cloudflare → Windows PC → localhost:8000 → Docker production backend`

Temporary tunnel:

`cloudflared tunnel --url http://localhost:8000`

Generated:

`https://xxxxx.trycloudflare.com`

Test:

`https://xxxxx.trycloudflare.com/docs`

Quick Tunnel URL is temporary.

Keep production on port `8000`.
Keep local development on port `8001` to avoid exposing dev through the production tunnel.


# Current architecture

DEV:

`Local backend :8001 → Dev Postgres :5433 → DEV volume`

PROD:

`Internet → Cloudflare → :8000 → Docker backend → Prod Postgres → PROD volume`

DEV and PROD use the same `postgres:16` image but separate:
- containers
- databases
- credentials
- networks
- volumes


# Production deployment flow

`main → build image → migrate PROD DB → recreate backend → verify`

Later automate this with CI/CD after tests pass.


# TODO

- Auto-start Docker/Desktop after Windows reboot
- DB backup/restore
- Permanent URL/domain
- Permanent Cloudflare Tunnel
- Production backend health check
- Automatic CI/CD from `main`
- Final reboot/recovery test

## Showcase database

The same PostgreSQL container hosts an independent Showcase database and role. Showcase owns its server-side access and repeatable schema initialization; Butler's Alembic only manages Butler. Follow [Showcase setup](project_source_of_truth/web/showcase/SHOWCASE.md). Provision existing volumes without resetting or deleting Butler data.
