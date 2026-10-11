import assert from "node:assert/strict";
import { after, before, describe, test } from "node:test";
import { randomBytes, scryptSync } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type Server } from "node:net";
import type { Server as HttpServer } from "node:http";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { once } from "node:events";
import { PGlite } from "@electric-sql/pglite";
import { PGLiteSocketServer } from "@electric-sql/pglite-socket";
import AdminJS from "adminjs";
import { Pool } from "pg";
import { generate } from "otplib";
import { crypto } from "@otplib/plugin-crypto-node";
import { TwoFactorStore } from "../src/two-factor.js";
import { decryptSecret, encryptSecret, acceptedStep } from "../src/totp.js";
import { createAuthenticator } from "../src/auth.js";
import { readAdminConfig, SESSION_MAX_AGE } from "../src/config.js";
import { createSessionStore } from "../src/sessions.js";
import { createAdminApp } from "../src/app.js";
import { databaseOptions, initializeDatabases } from "../src/database.js";

const password = "only-a-disposable-test-password";
const salt = randomBytes(16);
const env = {
  ADMIN_EMAIL: "admin@example.test",
  ADMIN_PASSWORD_HASH: `scrypt:${salt.toString("hex")}:${scryptSync(password, salt, 64).toString("hex")}`,
  ADMIN_SESSION_SECRET: randomBytes(48).toString("hex"),
  ADMIN_TOTP_ENCRYPTION_KEY: randomBytes(32).toString("hex"),
};
const config = readAdminConfig(env);

test("scrypt authentication accepts valid credentials and rejects incorrect or malformed credentials", async () => {
  const authenticate = createAuthenticator(config);
  assert.deepEqual(await authenticate(" ADMIN@example.test ", password), {
    email: env.ADMIN_EMAIL,
  });
  for (const [email, secret] of [
    [env.ADMIN_EMAIL, "incorrect"],
    ["other@example.test", password],
    [undefined, password],
    [env.ADMIN_EMAIL, null],
    [[env.ADMIN_EMAIL], password],
    ["a".repeat(255), password],
    [env.ADMIN_EMAIL, "x".repeat(1025)],
    [env.ADMIN_EMAIL, "é".repeat(513)],
    [env.ADMIN_EMAIL, ""],
  ])
    assert.equal(await authenticate(email, secret), null);
});

test("authentication and proxy configuration fail closed without exposing values", () => {
  for (const name of [
    "ADMIN_EMAIL",
    "ADMIN_PASSWORD_HASH",
    "ADMIN_SESSION_SECRET",
    "ADMIN_TOTP_ENCRYPTION_KEY",
  ] as const) {
    assert.throws(() => readAdminConfig({ ...env, [name]: undefined }));
  }
  for (const overrides of [
    { ADMIN_EMAIL: "malformed" },
    { ADMIN_PASSWORD_HASH: "scrypt:broken:hash" },
    { ADMIN_PASSWORD_HASH: "scrypt:" + "g".repeat(32) + ":" + "a".repeat(128) },
    { ADMIN_TOTP_ENCRYPTION_KEY: "broken" },
    { ADMIN_TOTP_ENCRYPTION_KEY: "g".repeat(64) },
    { ADMIN_SESSION_SECRET: " ".repeat(40) },
    { ADMIN_TRUST_PROXY: "true" },
    { NODE_ENV: "prod" },
  ])
    assert.throws(() => readAdminConfig({ ...env, ...overrides }));
  assert.equal(config.secureCookies, false);
  assert.equal(
    readAdminConfig({
      ...env,
      NODE_ENV: "production",
      ADMIN_TRUST_PROXY: "loopback",
    }).secureCookies,
    true,
  );
  assert.equal(config.trustProxy, false);
});

test("secret encryption authenticates ciphertext and context, with a small past-only TOTP window", async () => {
  const key = randomBytes(32);
  const secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
  const encrypted = encryptSecret(secret, key, "primary");
  assert.equal(decryptSecret(encrypted, key, "primary"), secret);
  assert.throws(() => decryptSecret(encrypted, key, "other"));
  assert.throws(() => decryptSecret(encrypted, randomBytes(32), "primary"));
  const parts = encrypted.split(":");
  parts[3] = (parts[3][0] === "a" ? "b" : "a") + parts[3].slice(1);
  assert.throws(() => decryptSecret(parts.join(":"), key, "primary"));
  const epoch = Math.floor(Date.now() / 30_000) * 30;
  const previous = await generate({ secret, crypto, epoch: epoch - 30 });
  const future = await generate({ secret, crypto, epoch: epoch + 30 });
  assert.equal(await acceptedStep(secret, previous, epoch * 1000, null), epoch / 30 - 1);
  assert.equal(await acceptedStep(secret, future, epoch * 1000, null), null);
  assert.equal(await acceptedStep(secret, previous, epoch * 1000, epoch / 30 - 1), null);
});

describe("AdminJS HTTP authorization with disposable PostgreSQL", { concurrency: false }, () => {
  const postgresInstances: PGlite[] = [];
  const postgresServers: PGLiteSocketServer[] = [];
  let dir: string;
  let pool: Pool;
  let options: {
    host: string;
    port: number;
    user: string;
    password: string;
    database: string;
  };
  let clock = Date.now();
  let factors: TwoFactorStore;
  let totpSecret: string;
  let recoveryCodes: string[];
  let databases: Awaited<ReturnType<typeof initializeDatabases>>;
  const servers = new Set<HttpServer>();
  const stores = new Set<Awaited<ReturnType<typeof createSessionStore>>>();
  const savedEnv = { ...process.env };

  before(async () => {
    dir = await mkdtemp(join(tmpdir(), "hello-butler-admin-test-"));
    async function database(name: string, table: string, title: string) {
      const db = await PGlite.create(join(dir, name));
      postgresInstances.push(db);
      await db.exec(
        `CREATE TABLE ${table} (id serial PRIMARY KEY, title text NOT NULL); INSERT INTO ${table}(title) VALUES ('${title}')`,
      );
      const reservation: Server = createServer();
      reservation.listen(0, "127.0.0.1");
      await once(reservation, "listening");
      const address = reservation.address();
      assert(address && typeof address !== "string");
      const port = address.port;
      await new Promise<void>((resolve) => reservation.close(() => resolve()));
      const server = new PGLiteSocketServer({
        db,
        host: "127.0.0.1",
        port,
        maxConnections: 30,
      });
      await server.start();
      postgresServers.push(server);
      return port;
    }
    const butlerPort = await database("butler", "butler_entries", "butler fixture");
    const showcasePort = await database("showcase", "showcase_entries", "showcase fixture");
    // PGlite sockets are disposable PostgreSQL protocol endpoints, not host DBs.
    options = {
      host: "127.0.0.1",
      port: showcasePort,
      user: "postgres",
      password: "test-only",
      database: "postgres",
    };
    pool = new Pool(options);
    Object.assign(process.env, {
      POSTGRES_DB: "postgres",
      POSTGRES_USER: options.user,
      POSTGRES_PASSWORD: options.password,
      POSTGRES_HOST: options.host,
      POSTGRES_PORT: String(butlerPort),
      SHOWCASE_DB: options.database,
      SHOWCASE_USER: options.user,
      SHOWCASE_PASSWORD: options.password,
      SHOWCASE_HOST: options.host,
      SHOWCASE_PORT: String(showcasePort),
    });
    // Create storage first, just like startup, then verify it is not exposed.
    const initialStore = await createSessionStore(options);
    await initialStore.close();
    factors = new TwoFactorStore(pool, config, () => clock);
    await factors.initialize();
    databases = await initializeDatabases();
  });

  after(async () => {
    for (const server of servers) await new Promise<void>((resolve) => server.close(() => resolve()));
    for (const storage of stores) await storage.close();
    if (databases) for (const database of databases) await database.tables()[0]?.knex.destroy();
    await pool?.end();
    for (const server of postgresServers) await server.stop();
    for (const db of postgresInstances) await db.close();
    if (dir) await rm(dir, { recursive: true, force: true });
    for (const key of Object.keys(process.env)) if (!(key in savedEnv)) delete process.env[key];
    Object.assign(process.env, savedEnv);
  });

  async function launch(production = false, overrides: Partial<typeof config> = {}) {
    const storage = await createSessionStore(options);
    stores.add(storage);
    const admin = new AdminJS({ rootPath: "/admin", databases });
    // Bundling static browser assets is unrelated to HTTP authorization tests.
    admin.initialize = async () => {};
    const app = createAdminApp(
      admin,
      production
        ? readAdminConfig({
            ...env,
            NODE_ENV: "production",
            ADMIN_TRUST_PROXY: "loopback",
          })
        : { ...config, ...overrides },
      storage.store,
      new TwoFactorStore(storage.pool, config, () => clock),
    );
    const server = app.listen(0, "127.0.0.1");
    servers.add(server);
    await once(server, "listening");
    const address = server.address();
    assert(address && typeof address !== "string");
    return {
      admin,
      url: `http://127.0.0.1:${address.port}`,
      close: async () => {
        await new Promise<void>((resolve) => server.close(() => resolve()));
        servers.delete(server);
        await storage.close();
        stores.delete(storage);
      },
    };
  }
  async function request(url: string, path: string, cookie?: string, init: RequestInit = {}) {
    return fetch(url + path, {
      ...init,
      redirect: "manual",
      headers: { ...(cookie ? { cookie } : {}), ...init.headers },
    });
  }
  function csrfFrom(html: string) {
    const token = html.match(/name="csrf" value="([0-9a-f]{64})"/);
    assert(token);
    return token[1];
  }
  async function login(url: string, email = env.ADMIN_EMAIL, secret = password, headers = {}, existingCookie?: string) {
    const form = await request(url, "/admin/login", existingCookie, {
      headers,
    });
    const csrf = csrfFrom(await form.text());
    const sessionCookie = form.headers.get("set-cookie") ? cookie(form) : existingCookie;
    return request(url, "/admin/login", sessionCookie, {
      method: "POST",
      body: new URLSearchParams({ email, password: secret, csrf }),
      headers,
    });
  }
  async function token(secret = totpSecret, advance = true) {
    if (advance) clock += 30_000;
    return generate({ secret, crypto, epoch: Math.floor(clock / 1000) });
  }
  async function submit(
    url: string,
    path: string,
    sessionCookie: string,
    values: Record<string, string>,
    headers = {},
  ) {
    const form = await request(url, path, sessionCookie, { headers });
    const csrf = csrfFrom(await form.text());
    return request(url, path, sessionCookie, {
      method: "POST",
      body: new URLSearchParams({ ...values, csrf }),
      headers,
    });
  }
  async function authenticatedLogin(
    url: string,
    email = env.ADMIN_EMAIL,
    secret = password,
    headers = {},
    existingCookie?: string,
  ) {
    const pending = await login(url, email, secret, headers, existingCookie);
    assert.equal(pending.headers.get("location"), "/admin/verify");
    return submit(url, "/admin/verify", cookie(pending), { code: await token(), kind: "totp" }, headers);
  }
  function cookie(response: Response) {
    const value = response.headers.get("set-cookie");
    assert(value);
    return value.split(";")[0];
  }

  test("first-time enrollment needs password, single-use operator grant and a valid code", async () => {
    const app = await launch();
    const pending = await login(app.url);
    const pendingCookie = cookie(pending);
    assert.equal(pending.headers.get("location"), "/admin/setup");
    assert.equal((await request(app.url, "/admin", pendingCookie)).status, 302);
    assert.equal(await factors.preview("unknown"), null);
    const invalid = await submit(app.url, "/admin/setup", pendingCookie, {
      token: "0".repeat(64),
    });
    assert.equal(invalid.status, 401);
    const grant = await factors.authorizeOperator();
    assert.equal((await submit(app.url, "/admin/setup", pendingCookie, { token: grant })).status, 302);
    const uri = await factors.preview(
      (await pool.query("SELECT id FROM admin_auth.challenge WHERE kind='enroll'")).rows[0].id,
    );
    assert(uri);
    assert.match(uri, /^otpauth:\/\/totp\//);
    totpSecret = new URL(uri).searchParams.get("secret")!;
    const qr = await request(app.url, "/admin/enroll", pendingCookie);
    assert.match(await qr.text(), /data:image\/png;base64/);
    assert.equal((await submit(app.url, "/admin/enroll", pendingCookie, { code: "bad" })).status, 401);
    assert.equal((await factors.status()).active, false);
    const enrolled = await submit(app.url, "/admin/enroll", pendingCookie, {
      code: await token(),
    });
    assert.equal(enrolled.status, 200);
    recoveryCodes = (await enrolled.text()).match(/[0-9a-f]{8}(?:-[0-9a-f]{8}){3}/g)!;
    assert.equal(recoveryCodes.length, 10);
    assert.equal((await request(app.url, "/admin", cookie(enrolled))).status, 200);
    assert.equal((await request(app.url, "/admin", pendingCookie)).status, 302);
    assert.equal((await pool.query("SELECT setup_hash FROM admin_auth.account")).rows[0].setup_hash, null);
    const row = (await pool.query("SELECT encrypted_secret FROM admin_auth.account")).rows[0];
    assert(!row.encrypted_secret.includes(totpSecret));
    assert.equal(decryptSecret(row.encrypted_secret, config.totpEncryptionKey, "primary"), totpSecret);
    const storedCodes = (await pool.query("SELECT hash FROM admin_auth.recovery_code")).rows;
    assert.equal(storedCodes.length, 10);
    assert(storedCodes.every((item) => !recoveryCodes.includes(item.hash)));
    await app.close();
  });

  test("unauthenticated dashboard, resources, and actions redirect to login", async () => {
    const app = await launch();
    for (const path of [
      "/admin",
      "/admin/resources/butler_entries",
      "/admin/api/resources/butler_entries/actions/list",
      "/admin/api/resources/showcase_entries/records/1/show",
    ]) {
      const response = await request(app.url, path);
      assert.equal(response.status, 302);
      assert.equal(response.headers.get("location"), "/admin/login");
    }
    const action = await request(app.url, "/admin/api/resources/butler_entries/actions/new", undefined, {
      method: "POST",
      body: new URLSearchParams({ title: "unauthorized" }),
    });
    assert.equal(action.status, 302);
    assert.equal(
      await databases[0]
        .table("butler_entries")
        .knex("butler_entries")
        .count()
        .first()
        .then((r) => Number(r?.count)),
      1,
    );
    await app.close();
  });

  test("login persists sessions and both SQL databases remain accessible without exposing storage", async () => {
    const app = await launch();
    assert.deepEqual(app.admin.resources.map((r) => r.id()).sort(), ["butler_entries", "showcase_entries"]);
    const response = await authenticatedLogin(app.url);
    assert.equal(response.status, 302);
    const header = response.headers.get("set-cookie")!;
    assert.match(header, /HttpOnly/);
    assert.match(header, /SameSite=Lax/);
    assert.match(header, /Path=\/admin/);
    assert.doesNotMatch(header, /; Secure/);
    const sessionCookie = cookie(response);
    const tampered = sessionCookie.slice(0, -1) + (sessionCookie.endsWith("a") ? "b" : "a");
    assert.equal((await request(app.url, "/admin", tampered)).status, 302);
    const rows = await pool.query(
      "SELECT sess, expire FROM admin_auth.session WHERE sess->'adminUser'->>'email' = $1",
      [env.ADMIN_EMAIL],
    );
    assert(rows.rowCount! > 0);
    assert(rows.rows.some((r) => r.sess.authenticatedAt && new Date(r.expire).getTime() > Date.now()));
    for (const [resource, title] of [
      ["butler_entries", "butler fixture"],
      ["showcase_entries", "showcase fixture"],
    ]) {
      const list = await request(app.url, `/admin/api/resources/${resource}/actions/list`, sessionCookie);
      assert.equal(list.status, 200);
      const data = await list.json();
      assert.equal(data.records[0].params.title, title);
    }
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 200);
    await app.close();
    const restarted = await launch();
    assert.equal((await request(restarted.url, "/admin", sessionCookie)).status, 200);
    await restarted.close();
  });

  test("incorrect email and password produce the same generic login failure", async () => {
    const app = await launch();
    const badEmail = await login(app.url, "wrong@example.test");
    const badPassword = await login(app.url, env.ADMIN_EMAIL, "wrong");
    assert.equal(badEmail.status, 401);
    assert.equal(badPassword.status, 401);
    assert.match(await badEmail.text(), /Unable to verify authentication/);
    assert.match(await badPassword.text(), /Unable to verify authentication/);
    assert.equal(badEmail.headers.get("set-cookie"), null);
    await app.close();
  });

  test("login rotates an existing session ID", async () => {
    const app = await launch();
    const original = cookie(await authenticatedLogin(app.url));
    const response = await authenticatedLogin(app.url, env.ADMIN_EMAIL, password, {}, original);
    const rotated = cookie(response);
    assert.notEqual(rotated, original);
    assert.equal((await request(app.url, "/admin", original)).status, 302);
    assert.equal((await request(app.url, "/admin", rotated)).status, 200);
    await app.close();
  });

  test("logout deletes the stored session and rejects replay", async () => {
    const app = await launch();
    const sessionCookie = cookie(await authenticatedLogin(app.url));
    const sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    const response = await submit(app.url, "/admin/logout", sessionCookie, {});
    assert.equal(response.status, 302);
    assert.match(response.headers.get("set-cookie")!, /Expires=Thu, 01 Jan 1970/);
    assert.equal((await pool.query("SELECT sid FROM admin_auth.session WHERE sid=$1", [sid])).rowCount, 0);
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    await app.close();
  });

  test("expired PostgreSQL sessions and absolute login expiry reject stale cookies", async () => {
    const app = await launch();
    let sessionCookie = cookie(await authenticatedLogin(app.url));
    let sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    await pool.query("UPDATE admin_auth.session SET expire = NOW() - INTERVAL '1 second' WHERE sid=$1", [sid]);
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    sessionCookie = cookie(await authenticatedLogin(app.url));
    sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    await pool.query(
      "UPDATE admin_auth.session SET sess = jsonb_set(sess::jsonb, '{authenticatedAt}', $1::jsonb)::json WHERE sid=$2",
      [JSON.stringify(Date.now() - SESSION_MAX_AGE - 1), sid],
    );
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    assert.equal((await pool.query("SELECT sid FROM admin_auth.session WHERE sid=$1", [sid])).rowCount, 0);
    await app.close();
  });

  test("login rejects excessive, duplicate, and unsupported form credentials", async () => {
    const app = await launch();
    assert.equal((await login(app.url, env.ADMIN_EMAIL, "x".repeat(5000))).status, 413);
    const json = await request(app.url, "/admin/login", undefined, {
      method: "POST",
      body: JSON.stringify({ email: env.ADMIN_EMAIL, password }),
      headers: { "content-type": "application/json" },
    });
    assert.equal(json.status, 400);
    const validForm = await request(app.url, "/admin/login");
    const duplicate = await request(app.url, "/admin/login", cookie(validForm), {
      method: "POST",
      body: new URLSearchParams([
        ["email", env.ADMIN_EMAIL],
        ["password", "wrong"],
        ["password", password],
        ["csrf", csrfFrom(await validForm.text())],
      ]),
    });
    assert.equal(duplicate.status, 401);
    assert.equal(duplicate.headers.get("set-cookie"), null);
    await app.close();
  });

  test("login throttles before expensive password verification", async () => {
    clock += 15 * 60 * 1000 + 1;
    const app = await launch();
    for (let i = 0; i < 10; i++) assert.equal((await login(app.url, env.ADMIN_EMAIL, "wrong")).status, 401);
    const blocked = await login(app.url);
    assert.equal(blocked.status, 429);
    assert(blocked.headers.get("retry-after"));
    assert.equal(blocked.headers.get("set-cookie"), null);
    await app.close();
    const restarted = await launch();
    assert.equal((await login(restarted.url)).status, 429);
    await restarted.close();
    clock += 15 * 60 * 1000 + 1;
  });

  test("production cookies require HTTPS and trust only an explicitly enabled local proxy", async () => {
    const app = await launch(true);
    const plaintextForm = await request(app.url, "/admin/login");
    assert.equal(plaintextForm.headers.get("set-cookie"), null);
    const secure = await authenticatedLogin(app.url, env.ADMIN_EMAIL, password, { "x-forwarded-proto": "https" });
    assert.match(secure.headers.get("set-cookie")!, /; Secure/);
    await app.close();
  });

  test("password-only and legacy sessions cannot access any AdminJS route or action", async () => {
    const app = await launch();
    const pending = await login(app.url);
    const pendingCookie = cookie(pending);
    const sid = decodeURIComponent(pendingCookie.split("=")[1]).slice(2).split(".")[0];
    const stored = (await pool.query("SELECT sess FROM admin_auth.session WHERE sid=$1", [sid])).rows[0].sess;
    assert.equal(stored.adminUser, undefined);
    assert.equal(stored.factorVerifiedAt, undefined);
    assert.equal(stored.pending.kind, "login");
    for (const path of [
      "/admin",
      "/admin/resources/butler_entries",
      "/admin/api/resources/showcase_entries/actions/list",
      "/admin/security",
      "/admin/replace",
    ]) {
      const response = await request(app.url, path, pendingCookie);
      assert.equal(response.status, 302);
      assert.equal(response.headers.get("location"), "/admin/login");
    }
    const write = await request(app.url, "/admin/api/resources/butler_entries/actions/new", pendingCookie, {
      method: "POST",
      body: new URLSearchParams({ title: "forbidden" }),
    });
    assert.equal(write.status, 302);
    const before = (await pool.query("SELECT sess FROM admin_auth.session WHERE sid=$1", [sid])).rows[0].sess;
    await pool.query("UPDATE admin_auth.session SET sess=$1::json WHERE sid=$2", [
      JSON.stringify({
        ...before,
        pending: undefined,
        adminUser: { email: config.email },
        authenticatedAt: clock,
      }),
      sid,
    ]);
    assert.equal((await request(app.url, "/admin", pendingCookie)).status, 302);
    assert.equal((await pool.query("SELECT sid FROM admin_auth.session WHERE sid=$1", [sid])).rowCount, 0);
    await app.close();
  });

  test("invalid, expired and previously consumed TOTP codes never complete login", async () => {
    const app = await launch();
    const code = await token();
    const pending = cookie(await login(app.url));
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          code: "bad",
          kind: "totp",
        })
      ).status,
      401,
    );
    const expired = await generate({
      secret: totpSecret,
      crypto,
      epoch: Math.floor(clock / 1000) - 90,
    });
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          code: expired,
          kind: "totp",
        })
      ).status,
      401,
    );
    const completed = await submit(app.url, "/admin/verify", pending, {
      code,
      kind: "totp",
    });
    assert.equal(completed.status, 302);
    assert.notEqual(cookie(completed), pending);
    const replay = cookie(await login(app.url));
    assert.equal((await submit(app.url, "/admin/verify", replay, { code, kind: "totp" })).status, 401);
    assert.equal((await request(app.url, "/admin", replay)).status, 302);
    await app.close();
  });

  test("concurrent TOTP verification accepts exactly one request", async () => {
    const app = await launch();
    const code = await token();
    const sessions = await Promise.all([login(app.url), login(app.url)]);
    const results = await Promise.all(
      sessions.map((response) =>
        submit(app.url, "/admin/verify", cookie(response), {
          code,
          kind: "totp",
        }),
      ),
    );
    assert.deepEqual(results.map((response) => response.status).sort(), [302, 401]);
    await app.close();
  });

  test("recovery requires password and atomically consumes one code under concurrency", async () => {
    const app = await launch();
    const anonymous = await request(app.url, "/admin/login");
    const anonCookie = cookie(anonymous);
    assert.equal(
      (
        await request(app.url, "/admin/verify", anonCookie, {
          method: "POST",
          body: new URLSearchParams({
            csrf: csrfFrom(await anonymous.text()),
            kind: "recovery",
            code: recoveryCodes[0],
          }),
        })
      ).status,
      401,
    );
    const pending = await Promise.all([login(app.url), login(app.url)]);
    const results = await Promise.all(
      pending.map((response) =>
        submit(app.url, "/admin/verify", cookie(response), {
          kind: "recovery",
          code: recoveryCodes[0],
        }),
      ),
    );
    assert.deepEqual(results.map((response) => response.status).sort(), [302, 401]);
    assert.equal((await pool.query("SELECT count(*) FROM admin_auth.recovery_code")).rows[0].count, "9");
    const replay = cookie(await login(app.url));
    assert.equal(
      (
        await submit(app.url, "/admin/verify", replay, {
          code: recoveryCodes[0],
          kind: "recovery",
        })
      ).status,
      401,
    );
    const challenge = await factors.beginLogin();
    const oneChallenge = await Promise.all([
      factors.verifyLogin(challenge.id, recoveryCodes[8], "recovery"),
      factors.verifyLogin(challenge.id, recoveryCodes[9], "recovery"),
    ]);
    assert.equal(oneChallenge.filter((value) => value !== null).length, 1);
    assert.equal((await pool.query("SELECT count(*) FROM admin_auth.recovery_code")).rows[0].count, "8");
    await app.close();
  });

  test("pending sessions expire and logout cancels pending challenges", async () => {
    const app = await launch();
    const pending = cookie(await login(app.url));
    const sid = decodeURIComponent(pending.split("=")[1]).slice(2).split(".")[0];
    const challengeId = (await pool.query("SELECT sess FROM admin_auth.session WHERE sid=$1", [sid])).rows[0].sess
      .pending.id;
    clock += 5 * 60 * 1000 + 1;
    assert.equal((await request(app.url, "/admin/verify", pending)).headers.get("location"), "/admin/login");
    assert.equal((await pool.query("SELECT id FROM admin_auth.challenge WHERE id=$1", [challengeId])).rowCount, 0);
    const next = cookie(await login(app.url));
    assert.equal((await submit(app.url, "/admin/logout", next, {})).status, 302);
    assert.equal((await request(app.url, "/admin/verify", next)).headers.get("location"), "/admin/login");
    await app.close();
  });

  test("CSRF protects login, setup, confirmation, replacement, recovery and logout", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    for (const path of [
      "/admin/login",
      "/admin/setup",
      "/admin/enroll",
      "/admin/verify",
      "/admin/replace",
      "/admin/security",
      "/admin/logout",
    ]) {
      const response = await request(app.url, path, authenticated, {
        method: "POST",
        body: new URLSearchParams({
          csrf: "bad",
          email: config.email,
          password,
          code: "123456",
          kind: "totp",
          operation: "recovery",
        }),
      });
      assert.equal(response.status, 403);
    }
    assert.equal((await request(app.url, "/admin", authenticated)).status, 200);
    await app.close();
  });

  test("verification attempts are capped per challenge and account lockout survives restart", async () => {
    const app = await launch();
    const code = await token();
    const pending = cookie(await login(app.url));
    for (let i = 0; i < 5; i++)
      assert.equal(
        (
          await submit(app.url, "/admin/verify", pending, {
            code: "bad",
            kind: "totp",
          })
        ).status,
        401,
      );
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          code,
          kind: "totp",
        })
      ).status,
      401,
    );
    const second = cookie(await login(app.url));
    for (let i = 0; i < 5; i++)
      assert.equal(
        (
          await submit(app.url, "/admin/verify", second, {
            code: "bad",
            kind: "totp",
          })
        ).status,
        401,
      );
    const locked = await submit(app.url, "/admin/verify", second, {
      code,
      kind: "totp",
    });
    assert.equal(locked.status, 429);
    assert(locked.headers.get("retry-after"));
    await app.close();
    const restarted = await launch();
    const next = cookie(await login(restarted.url));
    assert.equal(
      (
        await submit(restarted.url, "/admin/verify", next, {
          code,
          kind: "totp",
        })
      ).status,
      429,
    );
    clock += 15 * 60 * 1000 + 1;
    await restarted.close();
  });

  test("factor IP throttling runs before malformed form parsing or verification", async () => {
    const app = await launch();
    const before = (await pool.query("SELECT factor_failures FROM admin_auth.account")).rows[0].factor_failures;
    for (let i = 0; i < 30; i++) {
      const response = await request(app.url, "/admin/verify", undefined, {
        method: "POST",
        body: "{}",
        headers: { "content-type": "application/json" },
      });
      assert.equal(response.status, 400);
    }
    const blocked = await request(app.url, "/admin/verify", undefined, {
      method: "POST",
      body: "{}",
      headers: { "content-type": "application/json" },
    });
    assert.equal(blocked.status, 429);
    assert(blocked.headers.get("retry-after"));
    assert.equal((await pool.query("SELECT factor_failures FROM admin_auth.account")).rows[0].factor_failures, before);
    await app.close();
  });

  test("normal replacement requires fresh password and old factor before verifying the new secret", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    const oldSecret = totpSecret;
    const oldRecovery = [...recoveryCodes];
    const values = {
      password: "wrong",
      kind: "recovery",
      code: recoveryCodes[1],
      operation: "replace",
    };
    assert.equal((await submit(app.url, "/admin/security", authenticated, values)).status, 401);
    assert.equal(
      (
        await submit(app.url, "/admin/security", authenticated, {
          ...values,
          password,
        })
      ).status,
      302,
    );
    const sid = decodeURIComponent(authenticated.split("=")[1]).slice(2).split(".")[0];
    const id = (await pool.query("SELECT sess FROM admin_auth.session WHERE sid=$1", [sid])).rows[0].sess.replacement
      .id;
    const uri = await factors.preview(id);
    assert(uri);
    const newSecret = new URL(uri).searchParams.get("secret")!;
    assert.equal(
      (
        await submit(app.url, "/admin/replace", authenticated, {
          code: "bad",
        })
      ).status,
      401,
    );
    assert.equal(
      decryptSecret(
        (await pool.query("SELECT encrypted_secret FROM admin_auth.account")).rows[0].encrypted_secret,
        config.totpEncryptionKey,
        "primary",
      ),
      oldSecret,
    );
    const confirmed = await submit(app.url, "/admin/replace", authenticated, {
      code: await token(newSecret),
    });
    assert.equal(confirmed.status, 200);
    recoveryCodes = (await confirmed.text()).match(/[0-9a-f]{8}(?:-[0-9a-f]{8}){3}/g)!;
    totpSecret = newSecret;
    assert.equal(recoveryCodes.length, 10);
    assert.notDeepEqual(recoveryCodes, oldRecovery);
    assert.equal((await request(app.url, "/admin", authenticated)).status, 302);
    assert.equal((await request(app.url, "/admin", cookie(confirmed))).status, 200);
    const pending = cookie(await login(app.url));
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          kind: "totp",
          code: await token(oldSecret),
        })
      ).status,
      401,
    );
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          kind: "recovery",
          code: oldRecovery[2],
        })
      ).status,
      401,
    );
    await app.close();
  });

  test("regenerating recovery codes needs fresh factors and revokes older sessions/codes", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    const oldCodes = [...recoveryCodes];
    const result = await submit(app.url, "/admin/security", authenticated, {
      password,
      code: await token(),
      kind: "totp",
      operation: "recovery",
    });
    assert.equal(result.status, 200);
    recoveryCodes = (await result.text()).match(/[0-9a-f]{8}(?:-[0-9a-f]{8}){3}/g)!;
    assert.equal(recoveryCodes.length, 10);
    assert.notDeepEqual(recoveryCodes, oldCodes);
    assert.equal((await request(app.url, "/admin", authenticated)).status, 302);
    const newCookie = cookie(result);
    const security = await request(app.url, "/admin/security", newCookie);
    assert.doesNotMatch(await security.text(), /[0-9a-f]{8}(?:-[0-9a-f]{8}){3}/);
    const pending = cookie(await login(app.url));
    assert.equal(
      (
        await submit(app.url, "/admin/verify", pending, {
          kind: "recovery",
          code: oldCodes[0],
        })
      ).status,
      401,
    );
    await app.close();
  });

  test("configured credential changes revoke fully authenticated and pending sessions", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    const pending = cookie(await login(app.url));
    const salt = randomBytes(16);
    const changed = await launch(false, {
      passwordHash: `scrypt:${salt.toString("hex")}:${scryptSync("replacement-test-password", salt, 64).toString("hex")}`,
    });
    assert.equal((await request(changed.url, "/admin", authenticated)).status, 302);
    assert.equal(
      (await submit(changed.url, "/admin/verify", pending, { code: await token(), kind: "totp" })).status,
      401,
    );
    await changed.close();
    await app.close();
  });

  test("idempotent upgrade and restart preserve private state, sessions and content", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    const before = (await pool.query("SELECT * FROM admin_auth.account")).rows[0];
    const sessions = (await pool.query("SELECT count(*) FROM admin_auth.session")).rows[0].count;
    await factors.initialize();
    await factors.initialize();
    assert.deepEqual((await pool.query("SELECT * FROM admin_auth.account")).rows[0], before);
    assert.equal((await pool.query("SELECT count(*) FROM admin_auth.session")).rows[0].count, sessions);
    assert.equal((await pool.query("SELECT count(*) FROM showcase_entries")).rows[0].count, "1");
    assert.equal((await request(app.url, "/admin", authenticated)).status, 200);
    await app.close();
    const badKey = new TwoFactorStore(pool, { ...config, totpEncryptionKey: randomBytes(32) }, () => clock);
    await assert.rejects(badKey.initialize());
    const wrongAccount = new TwoFactorStore(pool, { ...config, email: "other@example.test" }, () => clock);
    await assert.rejects(wrongAccount.initialize());
  });

  test("operator upgrade is safe and reset requires explicit local confirmation", async () => {
    const execute = promisify(execFile);
    const operatorEnv = { ...process.env, ...env };
    const before = (await pool.query("SELECT * FROM admin_auth.account")).rows[0];
    const upgraded = await execute(process.execPath, ["--import", "tsx", "src/two-factor-operator.ts", "upgrade"], {
      env: operatorEnv,
    });
    assert.match(upgraded.stdout, /upgraded without resetting existing data/);
    assert.deepEqual((await pool.query("SELECT * FROM admin_auth.account")).rows[0], before);
    const rejected = await execute(process.execPath, ["--import", "tsx", "src/two-factor-operator.ts", "reset"], {
      env: operatorEnv,
    }).catch((error: { code: number; stdout: string; stderr: string }) => error);
    assert("code" in rejected && rejected.code === 1);
    assert.equal(rejected.stdout, "");
    assert.deepEqual((await pool.query("SELECT * FROM admin_auth.account")).rows[0], before);
  });

  test("operator reset revokes sessions and grants remain expiring, single-use and non-public", async () => {
    const app = await launch();
    const authenticated = cookie(await authenticatedLogin(app.url));
    assert.equal((await request(app.url, "/admin/reset", authenticated)).status, 404);
    await assert.rejects(factors.authorizeOperator());
    const lostKeyOperator = new TwoFactorStore(pool, { ...config, totpEncryptionKey: randomBytes(32) }, () => clock);
    await lostKeyOperator.initialize(false);
    await lostKeyOperator.emergencyReset();
    assert.equal((await request(app.url, "/admin", authenticated)).status, 302);
    let grant = await factors.authorizeOperator();
    clock += 10 * 60 * 1000 + 1;
    const pending = await factors.beginLogin();
    assert.equal(await factors.authorizeSetup(pending.id, grant), false);
    grant = await factors.authorizeOperator();
    const other = await factors.beginLogin();
    const results = await Promise.all([
      factors.authorizeSetup(pending.id, grant),
      factors.authorizeSetup(other.id, grant),
    ]);
    assert.deepEqual(results.sort(), [false, true]);
    assert.equal(await factors.authorizeSetup(other.id, grant), false);
    clock += 5 * 60 * 1000 + 1;
    assert.equal(await factors.preview(results[0] ? pending.id : other.id), null);
    assert.equal((await pool.query("SELECT count(*) FROM showcase_entries")).rows[0].count, "1");
    await app.close();
  });

  test("invalid database configuration fails before network access", () => {
    const original = process.env.SHOWCASE_PORT;
    process.env.SHOWCASE_PORT = "invalid";
    assert.throws(() =>
      databaseOptions("SHOWCASE_DB", "SHOWCASE_USER", "SHOWCASE_PASSWORD", "SHOWCASE_HOST", "SHOWCASE_PORT"),
    );
    process.env.SHOWCASE_PORT = original;
  });
});
