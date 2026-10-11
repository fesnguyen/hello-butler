# Admin authorization

**Status:** Implemented · **Authority:** [PROJECT_WEB.md](../PROJECT_WEB.md) → this document.

## Architecture and authentication boundary

`web/admin` remains a single-administrator Node/TypeScript Express service using AdminJS 7 and its SQL adapter. The administrator email and scrypt password hash come from the environment. AdminJS independently accesses the existing functional and Showcase databases through their `public` schemas; it does not proxy through FastAPI. Private authentication tables are in `admin_auth` in the Showcase database, outside adapter discovery.

`auth.ts` preserves asynchronous scrypt and timing-safe hash comparison. `app.ts` owns the two-stage HTTP login and the existing PostgreSQL-backed Express session. `two-factor.ts` owns transactional authentication state, `totp.ts` owns encryption and library-backed TOTP, and `auth-pages.ts` renders small responsive HTML forms. There is no additional authentication framework or user-management system.

The installed `@adminjs/express` 6.1.1 implementation was inspected: `withLogin()` immediately writes any truthy authentication callback result into `req.session.adminUser`; `withProtectedRoutesHandler()` checks only that field. Consequently a password-only callback cannot implement our boundary safely. Our login/enrollment/logout routes run before that router and terminate their requests. Its callback is deliberately `async () => null`. A gate before **all** remaining AdminJS routes, assets, APIs and actions requires a verified factor, matching administrator/credential fingerprint, unexpired login, and current account security version. Only our `complete()` function writes `adminUser`, after factor verification commits. The installed `express-session` middleware exits early if `req.session` already exists, so AdminJS reuses the same session/store rather than creating a second authentication session.

AdminJS authorization/resource permissions otherwise remain unchanged across both databases. `/admin/security` provides authenticator replacement and recovery-code regeneration. Open this address directly, or use the link on the recovery-code screen. Normal changes require a fully authenticated session plus fresh password and TOTP/recovery verification.

## Login and enrollment lifecycle

1. `GET /admin/login` issues a CSRF-protected credential form.
2. Successful email/password verification rotates the session and creates a database-backed, single-use pending challenge with a **five-minute** deadline. This session has no `adminUser` and cannot access AdminJS.
3. An enrolled account is redirected to `/admin/verify`. Select Authenticator code or Recovery code. A valid second factor consumes the challenge, rotates the session again, establishes the full identity, and redirects to the dashboard.
4. An account without an active authenticator is redirected to `/admin/setup`. The operator must explicitly authorize setup using the local command below. The setup token is random, stored only as a SHA-256 hash, expires in **ten minutes**, and is consumed atomically once. The administrator submits it only after the password step; it is never placed in a URL.
5. `/admin/enroll` displays a private QR code generated locally by `qrcode` from an `otpauth://` URI. In Google Authenticator or another standard TOTP app, add an account and scan the QR. Google Sign-In/OAuth is not involved. Enter the current six-digit code to activate the secret.
6. Activation consumes the challenge, increments the account version, rotates the session, and displays ten recovery codes once in the POST response. They are never stored in a session, logged, or available through a redisplay endpoint. Save them securely outside the browser. If the response is lost, sign in using a new TOTP code and regenerate them.

Setup authorization does not extend the original five-minute pending-login deadline. A consumed grant cannot be reused even when enrollment fails, is canceled, or expires; issue another grant and start a new password login. Unconfirmed secrets never become active. Expired challenges are removed when new ones are created; canceled challenges are removed on logout.

Normal authenticator replacement at `/admin/security` requires fresh password plus an existing factor. The old authenticator remains active until the new QR is confirmed within five minutes. Confirmation atomically replaces the secret, rotates recovery codes, and increments the security version, revoking older sessions and pending challenges. Recovery-code regeneration also requires fresh factors, replaces all code hashes, increments the version, and rotates the current session. Each use consumes a TOTP step or recovery code: wait for the next TOTP code if you just signed in. When using recovery, keep another unused code for the security change; if no working factor remains, use operator recovery.

## Cryptography and persistence

`otplib` 13 with its Node crypto plugin provides secret generation, URI generation and constant-time TOTP comparison; `qrcode` provides QR encoding. TOTP uses a random 20-byte Base32 secret, SHA-1, six digits and a 30-second period for standard authenticator compatibility. Verification accepts the current step and **one previous step only**, never a future step. Server and phone clocks must be synchronized. The exact matched time step is recorded and cannot be accepted again.

Secrets use Node's AES-256-GCM authenticated encryption with a fresh 12-byte nonce, 16-byte authentication tag and versioned encoding. Associated data binds active secrets to the primary account and unconfirmed secrets to their random challenge ID. Unconfirmed secrets are encrypted in the database, not stored in plaintext in sessions. Startup verifies an existing active secret with the configured key and refuses a mismatched key. Preserve the encryption key across deployments and backups.

The idempotent initialization in `two-factor-schema.ts` adds only:

| Table                      | Purpose                                                                                                                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `admin_auth.account`       | One stable `primary` account, email binding, encrypted active secret, activation/update times, last accepted step, security version, hashed setup grant/expiry, durable failure/lockout counters. |
| `admin_auth.challenge`     | Random session-bound challenge IDs, stage, encrypted unconfirmed secret, version, expiry and attempt count.                                                                                       |
| `admin_auth.recovery_code` | Ten SHA-256 hashes of random 128-bit recovery codes, plus creation timestamps.                                                                                                                    |

Schema/table PUBLIC privileges are revoked. A transaction-scoped advisory lock coordinates initialization. Existing administrator/session/content data is never overwritten by initialization. The configured email must match the stored account; changing it is an explicit operator data/configuration change, not a new account or implicit re-enrollment.

All factor verification, replay checks, grant/code consumption, security changes and counter updates lock the primary account row in a PostgreSQL transaction. Recovery consumption uses `DELETE ... RETURNING`; challenge consumption occurs in the same transaction. Concurrent verification cannot spend the same step, recovery code or pending challenge twice. Failed-attempt updates commit rather than being rolled back. Each pending challenge permits five failed attempts. Password and factor failure counters independently lock the account for fifteen minutes after ten failures within fifteen minutes; these counters persist across restarts. There is also the existing per-IP credential limit of ten POSTs per fifteen minutes and a factor/security limit of thirty POSTs per fifteen minutes, both before request parsing/session work. IP counters remain process-local for this single-instance service. Account-wide throttling can temporarily block legitimate access after repeated attacks; it expires automatically.

## Sessions and request protection

`connect-pg-simple` retains `admin_auth.session` with the existing small pool, `disableTouch`, and generic storage errors. Completed sessions use the existing eight-hour absolute expiry; pending cookies expire after five minutes. Cookies remain signed/opaque, HTTP-only, SameSite=Lax, scoped to `/admin`, and Secure under normal production HTTPS configuration. Both authentication transitions regenerate the session ID. Password/email configuration changes invalidate stored credential fingerprints. Security-version changes revoke all older sessions on their next request. Old password-only sessions lack the second-factor marker and are rejected/destroyed automatically after the upgrade; routine initialization never clears the session table.

Logout is a GET confirmation screen followed by CSRF-protected POST. POST cancels pending login/replacement challenges, destroys the stored session and clears the cookie. The existing AdminJS logout link therefore leads to the confirmation screen. Tampered, expired, revoked or incomplete sessions cannot access resources. Store/cryptography failures fail closed.

Login, setup, enrollment, verification, replacement, recovery regeneration and logout all validate session-bound CSRF tokens with timing-safe comparison. Authentication forms accept only small URL-encoded bodies, rejecting unsupported/chunked/lengthless or oversized requests before parsing. Pages use no-store, no-referrer, anti-framing headers and a restrictive CSP; QR images are local data URLs. Request/startup/operator failures use fixed messages. Never add request-body, session-payload, QR/URI, TOTP, recovery-code or credential logging. The operator command explicitly delivers a **setup authorization token** once to a private terminal; do not run authorization in CI or capture its output in application logs.

## Configuration

| Variable                    | Contract                                                                                                                                 |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `ADMIN_EMAIL`               | Existing valid email, compared case-insensitively after trimming; must match the stored primary account.                                 |
| `ADMIN_PASSWORD_HASH`       | Existing `scrypt:<32 hex salt characters>:<128 hex hash characters>`; retain the existing hash-generation utility.                       |
| `ADMIN_SESSION_SECRET`      | Existing high-entropy signing secret of at least 32 characters. Keep stable for normal deployments.                                      |
| `ADMIN_TOTP_ENCRYPTION_KEY` | **New, required:** independent 32-byte random encryption key encoded as exactly 64 hex characters. No default or password-only fallback. |
| `SHOWCASE_*`                | Existing Showcase connection; also owns private sessions and 2FA infrastructure.                                                         |
| `POSTGRES_*`                | Existing functional-database connection for AdminJS resources.                                                                           |

Generate the encryption key once in a private local terminal:

```sh
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

Save it in the ignored `.env.dev`/`.env.production` or the existing deployment secret store. Keep secure, separate backups of this key and the database. Do not regenerate it during builds or releases, commit it, put it in browser configuration, or reuse the session-signing secret as the encryption key.

Host database ports default to 5433; container connections use the existing `postgres:5432`. The default listener is 127.0.0.1:3000. Existing Compose binds the Admin container's port 3000 to **127.0.0.1:3001**, sets `ADMIN_LOCAL_HTTP=true`, and preserves the existing local HTTP cookie behavior. This exception is only for localhost/private use; it is not suitable for public plaintext HTTP. Normal production HTTPS uses Secure cookies and optionally `ADMIN_TRUST_PROXY=loopback` for a same-host trusted proxy that overwrites forwarded headers. Do not broaden proxy trust or expose the current localhost-only deployment as part of this change.

## Safe production upgrade and initial registration

Run from the repository root. Do not delete Docker volumes or reset either database.

1. Back up the existing Showcase database (including `admin_auth.session`) using the established PostgreSQL backup procedure; verify restoration separately. Securely back up deployment configuration. Record/preserve the currently deployed image before rebuilding. Add the new encryption key to `.env.production`, keeping existing password hash, signing secret and database settings.
2. Stop only AdminJS so the old password-only service cannot remain available during the upgrade:

   ```sh
   docker compose --env-file .env.production stop admin
   docker compose --env-file .env.production build admin
   ```

3. Explicitly upgrade the existing database using the new image and current environment. This does not depend on first-start PostgreSQL initialization scripts:

   ```sh
   docker compose --env-file .env.production run --rm --no-deps admin node --import ./dist/production.js dist/two-factor-operator.js upgrade
   ```

   The command is transactional/idempotent and leaves existing sessions and content intact. Missing key, wrong active-secret key, account mismatch, or database failure stops the command. New app startup runs the same initialization as a safety check; it never silently disables 2FA.

4. Start the updated service and issue an operator setup grant in a private terminal when ready to register:

   ```sh
   docker compose --env-file .env.production up -d --no-deps admin
   docker compose --env-file .env.production run --rm --no-deps admin node --import ./dist/production.js dist/two-factor-operator.js authorize
   ```

5. Open `http://localhost:3001/admin/login` for the existing local Compose deployment. Enter the existing email/password, then the setup token. Scan the QR, confirm its six-digit code, and save the recovery codes. Old sessions must redirect to login; knowing the password alone must never reach AdminJS.
6. Verify both databases remain available after full login, logout/replay rejection, invalid-code rejection, and service restart with the same key/database/signing secret. Verify actual PostgreSQL ownership/permissions and the real cookie/proxy transport before any remote deployment. No production database or configuration is modified by the automated tests.

For local development, put the same required settings in `.env.dev`, then from `web/admin` run `pnpm install --frozen-lockfile`, `pnpm 2fa:operator upgrade`, `pnpm dev`, and `pnpm 2fa:operator authorize`. Visit `http://localhost:3000/admin/login`. Keep the browser hostname consistent between localhost and 127.0.0.1.

## Emergency recovery and rollback

If the phone is lost but recovery codes remain, sign in with password + recovery code and replace the authenticator through `/admin/security` using fresh password + another recovery code. This needs no operator grant. Recovery codes never replace password verification.

For complete loss of both factors, an operator with trusted shell access, protected configuration and database privileges must stop AdminJS and explicitly reset only its authentication state:

```sh
docker compose --env-file .env.production stop admin
docker compose --env-file .env.production run --rm --no-deps admin node --import ./dist/production.js dist/two-factor-operator.js reset --confirm-reset
docker compose --env-file .env.production up -d --no-deps admin
docker compose --env-file .env.production run --rm --no-deps admin node --import ./dist/production.js dist/two-factor-operator.js authorize
```

Reset increments the account security version, removes its authenticator/recovery codes and pending grants/challenges, and clears lockouts. Existing sessions immediately fail the version/active-state gate; application tables and session rows are not reset. Re-enroll through password + the new short-lived grant. There is **no HTTP operator-reset or password-only bypass endpoint**. Rotate the password hash if compromise is suspected. If the encryption key itself is lost, stop the service, securely generate/store a replacement key, and run this explicit reset: only the reset command permits initialization without decrypting the old secret. Normal startup/upgrade still reject a wrong key. Old authenticator material cannot be recovered without its key.

For rollback, stop AdminJS first. A previous **2FA-capable** image may be restored with its compatible schema and the same encryption/signing keys; keep the private tables. This first 2FA release has no secure rollback to the earlier password-only image: keep AdminJS stopped and fix forward rather than reopening a password-only service. Do not drop authentication tables or restore/reset business databases to roll back code. Any database restore must use its matching encryption key; restoring older authentication state can revive spent recovery codes/replay counters, so perform operator reset and fresh enrollment before reopening access after such a restore.

## Verification and limits

```sh
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
```

The existing Node test suite exercises real AdminJS routes, scrypt, CSRF forms, signed cookies, production session storage and private SQL through two disposable file-backed PGlite databases with PostgreSQL wire sockets. Controlled clocks cover enrollment, expiration, replay, clock skew and lockouts. Concurrent requests cover TOTP, recovery-code and challenge/grant consumption. Tests also cover replacement/regeneration, session rotation/revocation/logout, persisted state after restart, legacy-session rejection, encryption tampering/wrong keys, inaccessible APIs and idempotent upgrades preserving content.

Admin currently has no configured lint script; static type checks and a separate ESLint review cover the changed TypeScript. PGlite cannot validate real PostgreSQL privileges or the production proxy, and simulated concurrent requests do not replace a real PostgreSQL deployment check. Phone scanning, real production permissions, backup/restore and transport verification remain operator checks. There are no device-trust bypasses, Google OAuth, additional administrators, background infrastructure or new database servers.

Library references: [otplib verification/replay API](https://otplib.yeojz.dev/guide/advanced-usage), [node-qrcode](https://github.com/soldair/node-qrcode). The local installed AdminJS/Express source, rather than an assumed callback contract, determines the authentication boundary described above.
