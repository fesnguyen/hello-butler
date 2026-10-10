import assert from "node:assert/strict";
import { after, before, describe, test } from "node:test";
import { randomBytes, scryptSync } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type Server } from "node:net";
import type { Server as HttpServer } from "node:http";
import { once } from "node:events";
import { PGlite } from "@electric-sql/pglite";
import { PGLiteSocketServer } from "@electric-sql/pglite-socket";
import AdminJS from "adminjs";
import { Pool } from "pg";
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
};
const config = readAdminConfig(env);

test("scrypt authentication accepts valid credentials and rejects incorrect or malformed credentials", async () => {
  const authenticate = createAuthenticator(config);
  assert.deepEqual(await authenticate(" ADMIN@example.test ", password), { email: env.ADMIN_EMAIL });
  for (const [email, secret] of [
    [env.ADMIN_EMAIL, "incorrect"], ["other@example.test", password],
    [undefined, password], [env.ADMIN_EMAIL, null], [[env.ADMIN_EMAIL], password],
    ["a".repeat(255), password], [env.ADMIN_EMAIL, "x".repeat(1025)],
    [env.ADMIN_EMAIL, "é".repeat(513)], [env.ADMIN_EMAIL, ""],
  ]) assert.equal(await authenticate(email, secret), null);
});

test("authentication and proxy configuration fail closed without exposing values", () => {
  for (const name of ["ADMIN_EMAIL", "ADMIN_PASSWORD_HASH", "ADMIN_SESSION_SECRET"] as const) {
    assert.throws(() => readAdminConfig({ ...env, [name]: undefined }));
  }
  for (const overrides of [
    { ADMIN_EMAIL: "malformed" }, { ADMIN_PASSWORD_HASH: "scrypt:broken:hash" },
    { ADMIN_PASSWORD_HASH: "scrypt:" + "g".repeat(32) + ":" + "a".repeat(128) },
    { ADMIN_SESSION_SECRET: " ".repeat(40) }, { ADMIN_TRUST_PROXY: "true" }, { NODE_ENV: "prod" },
  ]) assert.throws(() => readAdminConfig({ ...env, ...overrides }));
  assert.equal(config.secureCookies, false);
  assert.equal(readAdminConfig({ ...env, NODE_ENV: "production", ADMIN_TRUST_PROXY: "loopback" }).secureCookies, true);
  assert.equal(config.trustProxy, false);
});

describe("AdminJS HTTP authorization with disposable PostgreSQL", { concurrency: false }, () => {
  const postgresInstances: PGlite[] = [];
  const postgresServers: PGLiteSocketServer[] = [];
  let dir: string;
  let pool: Pool;
  let options: { host: string; port: number; user: string; password: string; database: string };
  let databases: Awaited<ReturnType<typeof initializeDatabases>>;
  const servers = new Set<HttpServer>();
  const stores = new Set<Awaited<ReturnType<typeof createSessionStore>>>();
  const savedEnv = { ...process.env };

  before(async () => {
    dir = await mkdtemp(join(tmpdir(), "hello-butler-admin-test-"));
    async function database(name: string, table: string, title: string) {
      const db = await PGlite.create(join(dir, name));
      postgresInstances.push(db);
      await db.exec(`CREATE TABLE ${table} (id serial PRIMARY KEY, title text NOT NULL); INSERT INTO ${table}(title) VALUES ('${title}')`);
      const reservation: Server = createServer();
      reservation.listen(0, "127.0.0.1");
      await once(reservation, "listening");
      const address = reservation.address();
      assert(address && typeof address !== "string");
      const port = address.port;
      await new Promise<void>((resolve) => reservation.close(() => resolve()));
      const server = new PGLiteSocketServer({ db, host: "127.0.0.1", port, maxConnections: 30 });
      await server.start();
      postgresServers.push(server);
      return port;
    }
    const butlerPort = await database("butler", "butler_entries", "butler fixture");
    const showcasePort = await database("showcase", "showcase_entries", "showcase fixture");
    // PGlite sockets are disposable PostgreSQL protocol endpoints, not host DBs.
    options = { host: "127.0.0.1", port: showcasePort, user: "postgres", password: "test-only", database: "postgres" };
    pool = new Pool(options);
    Object.assign(process.env, {
      POSTGRES_DB: "postgres", POSTGRES_USER: options.user, POSTGRES_PASSWORD: options.password,
      POSTGRES_HOST: options.host, POSTGRES_PORT: String(butlerPort),
      SHOWCASE_DB: options.database, SHOWCASE_USER: options.user, SHOWCASE_PASSWORD: options.password,
      SHOWCASE_HOST: options.host, SHOWCASE_PORT: String(showcasePort),
    });
    // Create storage first, just like startup, then verify it is not exposed.
    const initialStore = await createSessionStore(options);
    await initialStore.close();
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

  async function launch(production = false) {
    const storage = await createSessionStore(options);
    stores.add(storage);
    const admin = new AdminJS({ rootPath: "/admin", databases });
    // Bundling static browser assets is unrelated to HTTP authorization tests.
    admin.initialize = async () => {};
    const app = createAdminApp(admin, production ? readAdminConfig({ ...env, NODE_ENV: "production", ADMIN_TRUST_PROXY: "loopback" }) : config, storage.store);
    const server = app.listen(0, "127.0.0.1");
    servers.add(server);
    await once(server, "listening");
    const address = server.address();
    assert(address && typeof address !== "string");
    return {
      admin, url: `http://127.0.0.1:${address.port}`,
      close: async () => {
        await new Promise<void>((resolve) => server.close(() => resolve())); servers.delete(server);
        await storage.close(); stores.delete(storage);
      },
    };
  }
  async function request(url: string, path: string, cookie?: string, init: RequestInit = {}) {
    return fetch(url + path, { ...init, redirect: "manual", headers: { ...(cookie ? { cookie } : {}), ...init.headers } });
  }
  async function login(url: string, email = env.ADMIN_EMAIL, secret = password, headers = {}) {
    return request(url, "/admin/login", undefined, { method: "POST", body: new URLSearchParams({ email, password: secret }), headers });
  }
  function cookie(response: Response) {
    const value = response.headers.get("set-cookie"); assert(value); return value.split(";")[0];
  }

  test("unauthenticated dashboard, resources, and actions redirect to login", async () => {
    const app = await launch();
    for (const path of ["/admin", "/admin/resources/butler_entries", "/admin/api/resources/butler_entries/actions/list", "/admin/api/resources/showcase_entries/records/1/show"]) {
      const response = await request(app.url, path);
      assert.equal(response.status, 302); assert.equal(response.headers.get("location"), "/admin/login");
    }
    const action = await request(app.url, "/admin/api/resources/butler_entries/actions/new", undefined, { method: "POST", body: new URLSearchParams({ title: "unauthorized" }) });
    assert.equal(action.status, 302);
    assert.equal(await databases[0].table("butler_entries").knex("butler_entries").count().first().then((r) => Number(r?.count)), 1);
    await app.close();
  });

  test("login persists sessions and both SQL databases remain accessible without exposing storage", async () => {
    const app = await launch();
    assert.deepEqual(app.admin.resources.map((r) => r.id()).sort(), ["butler_entries", "showcase_entries"]);
    const response = await login(app.url); assert.equal(response.status, 302);
    const header = response.headers.get("set-cookie")!;
    assert.match(header, /HttpOnly/); assert.match(header, /SameSite=Lax/); assert.match(header, /Path=\/admin/); assert.doesNotMatch(header, /; Secure/);
    const sessionCookie = cookie(response);
    const tampered = sessionCookie.slice(0, -1) + (sessionCookie.endsWith("a") ? "b" : "a");
    assert.equal((await request(app.url, "/admin", tampered)).status, 302);
    const rows = await pool.query("SELECT sess, expire FROM admin_auth.session WHERE sess->'adminUser'->>'email' = $1", [env.ADMIN_EMAIL]);
    assert(rows.rowCount! > 0);
    assert(rows.rows.some((r) => r.sess.authenticatedAt && new Date(r.expire).getTime() > Date.now()));
    for (const [resource, title] of [["butler_entries", "butler fixture"], ["showcase_entries", "showcase fixture"]]) {
      const list = await request(app.url, `/admin/api/resources/${resource}/actions/list`, sessionCookie);
      assert.equal(list.status, 200); const data = await list.json(); assert.equal(data.records[0].params.title, title);
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
    assert.equal(badEmail.status, 200); assert.equal(badPassword.status, 200);
    assert.equal(await badEmail.text(), await badPassword.text());
    assert.equal(badEmail.headers.get("set-cookie"), null);
    await app.close();
  });

  test("login rotates an existing session ID", async () => {
    const app = await launch();
    const original = cookie(await login(app.url));
    const response = await request(app.url, "/admin/login", original, { method: "POST", body: new URLSearchParams({ email: env.ADMIN_EMAIL, password }) });
    const rotated = cookie(response); assert.notEqual(rotated, original);
    assert.equal((await request(app.url, "/admin", original)).status, 302);
    assert.equal((await request(app.url, "/admin", rotated)).status, 200);
    await app.close();
  });

  test("logout deletes the stored session and rejects replay", async () => {
    const app = await launch(); const sessionCookie = cookie(await login(app.url));
    const sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    const response = await request(app.url, "/admin/logout", sessionCookie);
    assert.equal(response.status, 302); assert.match(response.headers.get("set-cookie")!, /Expires=Thu, 01 Jan 1970/);
    assert.equal((await pool.query("SELECT sid FROM admin_auth.session WHERE sid=$1", [sid])).rowCount, 0);
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    await app.close();
  });

  test("expired PostgreSQL sessions and absolute login expiry reject stale cookies", async () => {
    const app = await launch();
    let sessionCookie = cookie(await login(app.url));
    let sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    await pool.query("UPDATE admin_auth.session SET expire = NOW() - INTERVAL '1 second' WHERE sid=$1", [sid]);
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    sessionCookie = cookie(await login(app.url)); sid = decodeURIComponent(sessionCookie.split("=")[1]).slice(2).split(".")[0];
    await pool.query("UPDATE admin_auth.session SET sess = jsonb_set(sess::jsonb, '{authenticatedAt}', $1::jsonb)::json WHERE sid=$2", [JSON.stringify(Date.now() - SESSION_MAX_AGE - 1), sid]);
    assert.equal((await request(app.url, "/admin", sessionCookie)).status, 302);
    assert.equal((await pool.query("SELECT sid FROM admin_auth.session WHERE sid=$1", [sid])).rowCount, 0);
    await app.close();
  });

  test("login rejects excessive, duplicate, and unsupported form credentials", async () => {
    const app = await launch();
    assert.equal((await login(app.url, env.ADMIN_EMAIL, "x".repeat(5000))).status, 413);
    const json = await request(app.url, "/admin/login", undefined, { method: "POST", body: JSON.stringify({ email: env.ADMIN_EMAIL, password }), headers: { "content-type": "application/json" } });
    assert.equal(json.status, 400);
    const duplicate = await request(app.url, "/admin/login", undefined, { method: "POST", body: new URLSearchParams([["email", env.ADMIN_EMAIL], ["password", "wrong"], ["password", password]]) });
    assert.equal(duplicate.headers.get("set-cookie"), null);
    await app.close();
  });

  test("login throttles before expensive password verification", async () => {
    const app = await launch();
    for (let i = 0; i < 10; i++) assert.equal((await login(app.url, env.ADMIN_EMAIL, "wrong")).status, 200);
    const blocked = await login(app.url);
    assert.equal(blocked.status, 429); assert(blocked.headers.get("retry-after")); assert.equal(blocked.headers.get("set-cookie"), null);
    await app.close();
  });

  test("production cookies require HTTPS and trust only an explicitly enabled local proxy", async () => {
    const app = await launch(true);
    assert.equal((await login(app.url)).headers.get("set-cookie"), null);
    const secure = await login(app.url, env.ADMIN_EMAIL, password, { "x-forwarded-proto": "https" });
    assert.match(secure.headers.get("set-cookie")!, /; Secure/);
    await app.close();
  });

  test("invalid database configuration fails before network access", () => {
    const original = process.env.SHOWCASE_PORT;
    process.env.SHOWCASE_PORT = "invalid";
    assert.throws(() => databaseOptions("SHOWCASE_DB", "SHOWCASE_USER", "SHOWCASE_PASSWORD", "SHOWCASE_HOST", "SHOWCASE_PORT"));
    process.env.SHOWCASE_PORT = original;
  });
});
