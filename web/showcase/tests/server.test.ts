import assert from 'node:assert/strict';
import { test } from 'node:test';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { databaseConfig } from '../server/config.ts';
import { applicationsSql } from '../server/data.ts';
import { showcaseServer } from '../server/http.ts';
import type { ShowcaseApplication } from '../src/api.ts';

const schema = await readFile(
  new URL('../db/schema.sql', import.meta.url),
  'utf8',
);
const seed = await readFile(new URL('../db/seed.sql', import.meta.url), 'utf8');

test('PostgreSQL schema, seed, ordering and latest published release', async () => {
  const db = new PGlite();
  try {
    await db.exec(schema);
    await db.exec(schema);
    assert.deepEqual(
      (
        await db.query<{ tablename: string }>(
          "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
        )
      ).rows.map((r) => r.tablename),
      ['applications', 'media', 'releases'],
    );
    assert.deepEqual((await db.query(applicationsSql)).rows, []);
    await db.exec(seed);
    await db.exec(seed);
    let apps = (await db.query<ShowcaseApplication>(applicationsSql)).rows;
    assert.equal(apps.length, 1);
    const id = apps[0]!.id;
    assert.equal(apps[0]!.media.length, 0);
    assert.equal(apps[0]!.latest_release, null);
    await db.query('UPDATE applications SET github_url = NULL WHERE id=$1', [
      id,
    ]);
    for (const [mid, order] of [
      ['00000000-0000-4000-8000-000000000002', 2],
      ['00000000-0000-4000-8000-000000000001', 0],
    ] as const) {
      await db.query('INSERT INTO media VALUES ($1,$2,$3,$4)', [
        mid,
        id,
        `https://example.com/${order}.png`,
        order,
      ]);
    }
    for (const [rid, version, date] of [
      ['00000000-0000-4000-8000-000000000001', 'old', '2020-01-01T00:00:00Z'],
      [
        '00000000-0000-4000-8000-000000000002',
        'latest',
        '2021-01-01T00:00:00Z',
      ],
      [
        '00000000-0000-4000-8000-000000000003',
        'future',
        '2099-01-01T00:00:00Z',
      ],
    ] as const) {
      await db.query('INSERT INTO releases VALUES ($1,$2,$3,$4,$5)', [
        rid,
        id,
        version,
        'https://example.com/app.apk',
        date,
      ]);
    }
    apps = (await db.query<ShowcaseApplication>(applicationsSql)).rows;
    assert.equal(apps[0]!.github_url, null);
    assert.deepEqual(
      apps[0]!.media.map((m) => m.display_order),
      [0, 2],
    );
    assert.equal(apps[0]!.latest_release!.version, 'latest');
    await db.exec(schema);
    assert.equal((await db.query('SELECT * FROM media')).rows.length, 2);
    await assert.rejects(
      db.query('INSERT INTO media VALUES ($1,$2,$3,0)', [
        '00000000-0000-4000-8000-000000000099',
        '00000000-0000-4000-8000-000000000099',
        'https://example.com/missing.png',
      ]),
    );
  } finally {
    await db.close();
  }
});

test('legacy content is adopted without losing rows', async () => {
  const db = new PGlite();
  try {
    await db.exec(schema);
    await db.exec(seed);
    await db.exec(
      'ALTER TABLE applications RENAME TO showcase_applications; ALTER TABLE media RENAME TO showcase_media; ALTER TABLE releases RENAME TO showcase_releases;',
    );
    await db.exec(schema);
    assert.equal((await db.query(applicationsSql)).rows.length, 1);
  } finally {
    await db.close();
  }
});

test('server-only configuration rejects shared databases/users and missing secrets', () => {
  const env = {
    SHOWCASE_DB: 'showcase_test',
    SHOWCASE_USER: 'showcase_test',
    SHOWCASE_PASSWORD: 'test-only',
  };
  assert.equal(databaseConfig(env).database, 'showcase_test');
  assert.throws(
    () => databaseConfig({ ...env, POSTGRES_DB: env.SHOWCASE_DB }),
    /differ/,
  );
  assert.throws(
    () => databaseConfig({ ...env, POSTGRES_USER: env.SHOWCASE_USER }),
    /differ/,
  );
  assert.throws(
    () => databaseConfig({ ...env, SHOWCASE_PASSWORD: '' }),
    /required/,
  );
  assert.throws(
    () => databaseConfig({ ...env, SHOWCASE_DB: 'hello_butler_dev' }),
    /SHOWCASE_DB/,
  );
});

test('HTTP boundary is read-only, serves empty data and hides failures', async () => {
  let fail = false;
  const server = showcaseServer(async () => {
    if (fail) throw new Error('private-password');
    return [];
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  const origin = `http://127.0.0.1:${address.port}`;
  try {
    assert.deepEqual(
      await (await fetch(`${origin}/api/applications`)).json(),
      [],
    );
    assert.equal(
      (await fetch(`${origin}/api/applications`, { method: 'POST' })).status,
      405,
    );
    assert.equal((await fetch(`${origin}/api/private`)).status, 404);
    fail = true;
    const response = await fetch(`${origin}/api/applications`);
    assert.equal(response.status, 503);
    assert.doesNotMatch(await response.text(), /private-password/);
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});
