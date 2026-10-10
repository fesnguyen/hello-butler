# Admin authorization

**Status:** Implemented · **Authority:** [PROJECT_WEB.md](../PROJECT_WEB.md) → this document.

## Architecture and access

`web/admin` is a Node/TypeScript Express service using AdminJS 7 and the AdminJS SQL adapter. Its server connects independently to the Hello Butler functional database (`POSTGRES_*`) and Showcase database (`SHOWCASE_*`). Both remain available in the authenticated dashboard. The browser receives no PostgreSQL credentials and AdminJS does not proxy through FastAPI. Adapter discovery explicitly uses the existing `public` schemas.

`src/config.ts` validates authentication configuration, `src/auth.ts` verifies the existing scrypt password format, `src/sessions.ts` owns PostgreSQL session storage, and `src/app.ts` composes the existing `buildAuthenticatedRouter()` with request protections. `src/index.ts` validates configuration and initializes storage/databases before listening. The listener remains **127.0.0.1**; there is no public bind or deployment change.

There is one environment-configured administrator. Successful login grants the existing AdminJS resource/action permissions across both databases. This is an authentication boundary, not a per-user role system. Registration, password resets, administrator management, and business authorization changes are out of scope.

## Required configuration

| Variable | Contract |
| --- | --- |
| `ADMIN_EMAIL` | Valid email, at most 254 characters; compared case-insensitively after trimming. |
| `ADMIN_PASSWORD_HASH` | Existing `scrypt:<salt hex>:<hash hex>` format: 16-byte salt and 64-byte hash. No plaintext password is stored. |
| `ADMIN_SESSION_SECRET` | At least 32 characters after trimming; generate a high-entropy random secret and keep it stable across restarts. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Existing functional database access. |
| `SHOWCASE_DB`, `SHOWCASE_USER`, `SHOWCASE_PASSWORD` | Existing Showcase access, also used for isolated Admin session storage. |

`POSTGRES_HOST` and `SHOWCASE_HOST` default to `localhost`; their corresponding `*_PORT` values default to `5433` and must be integer ports in 1–65535. `PORT` defaults to `3000` with the same bounds. Keep Showcase and functional databases separate as already provisioned.

`NODE_ENV` may be unset, `development`, `test`, or `production`. `ADMIN_TRUST_PROXY` may be unset (no forwarded-header trust) or exactly `loopback`. The production command forces `NODE_ENV=production`; secure cookies cannot accidentally be disabled by an omitted setting. Never commit environment files or actual credentials. Startup/request/storage errors use fixed messages so driver error details cannot disclose connection strings or secrets.

## Sessions

`connect-pg-simple` replaces Express MemoryStore using a small `pg` pool connected to the existing Showcase database. On startup, the service creates **only** `admin_auth.session` and its infrastructure schema/index if missing. No application schema migration or new business model is introduced. The Showcase role must own or be allowed to create/use this schema and session table. PUBLIC schema/table privileges are revoked. Use one instance for first-time provisioning; the upstream store's initial table creation is not a coordinated multi-instance migration.

The session schema is outside `public` and is never discovered as an AdminJS resource. It contains session IDs, the administrator email, login time, cookie metadata, and expiry; treat its data and backups as sensitive. The browser holds a signed, opaque session ID, not the stored session payload.

The cookie is named `hello-butler-admin`, scoped to `/admin`, HTTP-only, and SameSite=Lax. It expires after eight hours and is Secure in production. Login regenerates the session ID before AdminJS saves the identity. PostgreSQL expiry rejects stale sessions even before periodic pruning (the store's default interval is about 15 minutes). Touch-based extension is disabled, and an absolute eight-hour login-time check prevents resource requests from extending authorization. Logout deletes the server-side session and clears the cookie; replay of the old cookie is rejected. Store errors fail requests closed.

Application restarts preserve sessions when the database and signing secret are retained. Rotating the secret invalidates existing cookies. Changing configured credentials does **not** automatically revoke already authenticated sessions; for an emergency credential rotation, also rotate the session secret or delete sessions. Do not clear the table during routine startup.

## Login protections

Login POSTs are limited to **10 requests per client IP per 15 minutes**, including successful and malformed attempts. Excess requests return 429 with Retry-After. The limiter executes before body parsing, database/session work, and scrypt verification. Its counters are process-local and reset on restart; they are intended for this single localhost-bound service. Multiple replicas would need shared counters before deployment.

The standard AdminJS browser form sends URL-encoded credentials. Login rejects other content types, chunked/lengthless requests, and bodies over 4096 bytes before AdminJS's multipart parser runs. Email and password must be single, nonempty strings; email is capped at 254 characters and password at 1024 UTF-8 bytes. Duplicate fields and oversized/malformed values cannot authenticate. These login-specific limits do not restrict normal AdminJS resource forms or uploads.

Password verification retains Node's asynchronous scrypt and timing-safe hash comparison, including verification for an incorrect email. Incorrect email/password share the existing generic AdminJS error. AdminJS's authenticated router gates dashboard, resource pages, and API actions. Public login/static assets expose no database records. No credential, token, or session ID is logged.

## Development and production

For localhost HTTP development, populate the required variables in the ignored root `.env.dev` file, then run from `web/admin`:

```sh
pnpm install --frozen-lockfile
pnpm dev
```

Use the existing `generate-admin-hash.mjs` utility to generate the expected hash locally; keep its result private. With no proxy trust and nonproduction mode, cookies work over `http://localhost:3000/admin`. A browser change between `localhost` and `127.0.0.1` changes the cookie host.

For production, populate the ignored `.env.production`, run `pnpm build`, then `pnpm start`. Serve HTTPS through a trusted reverse proxy on the same host and explicitly set `ADMIN_TRUST_PROXY=loopback`. The proxy must overwrite forwarded protocol/client-IP headers and enforce HTTPS. The service remains an HTTP listener reachable only on loopback; plain HTTP production login deliberately issues no session cookie. Do not enable broad proxy trust or expose the listener directly.

Docker migration is **not implemented here**. Retain the session database/secret and provision the session schema with appropriate ownership when migrating. Container networking cannot reuse a host-loopback proxy assumption without a separate reviewed binding/trust configuration. Keep the Admin session schema out of Showcase content export/import and public APIs. Monitor session database availability, clean expired rows through the store, and back up/protect the PostgreSQL instance according to existing operations.

## Verification

```sh
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
```

The Admin CI job runs all these checks on the existing Windows runner. Node's test runner exercises actual AdminJS HTTP routes, scrypt verification, signed cookies, and the production `connect-pg-simple` store through `pg`. Two disposable, file-backed PGlite databases expose PostgreSQL protocol sockets on loopback ephemeral ports. Tests create their own credentials and fixtures, restore environment changes, and clean up storage; no environment files or production databases are used. Asset bundling is skipped inside HTTP tests. PGlite is a test-only PostgreSQL WASM implementation and does not validate production PostgreSQL permissions, network failures, or reverse-proxy behavior.

Before production use, manually verify session-schema ownership/permissions on the real PostgreSQL instance, HTTPS cookie delivery through the actual proxy, login/logout, restart with the same secret, and access to both existing databases. Changing local real database contents is not part of automated verification.
