import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderToStaticMarkup } from 'react-dom/server';
import { ApplicationCard } from '../src/ApplicationCard';
import { applicationsUrl, fetchApplications } from '../src/api';
import type { ShowcaseApplication } from '../src/api';

const application: ShowcaseApplication = {
  id: 'app-id',
  name: 'Test application',
  description: 'From the database, not a static component.',
  github_url: null,
  media: [],
  latest_release: null,
};

test('missing media, source, and release have an intentional state', () => {
  const html = renderToStaticMarkup(
    <ApplicationCard application={application} />,
  );
  assert.match(html, /Test application/);
  assert.match(html, /From the database/);
  assert.match(html, /No downloadable release yet/);
  assert.doesNotMatch(html, /<img|View source|href=/);
});

test('source, ordered media, and latest download are rendered from API data', () => {
  const html = renderToStaticMarkup(
    <ApplicationCard
      application={{
        ...application,
        github_url: 'https://github.com/example/project',
        media: [
          {
            id: 'second',
            url: 'https://example.com/second.png',
            display_order: 2,
          },
          {
            id: 'first',
            url: 'https://example.com/first.png',
            display_order: 0,
          },
        ],
        latest_release: {
          version: '1.2.3',
          download_url: 'https://example.com/app.apk',
          published_at: '2026-10-01T12:00:00Z',
        },
      }}
    />,
  );
  assert.ok(html.indexOf('first.png') < html.indexOf('second.png'));
  assert.match(html, /href="https:\/\/github.com\/example\/project"/);
  assert.match(html, /href="https:\/\/example.com\/app.apk"/);
  assert.match(html, /v1.2.3/);
  assert.doesNotMatch(html, /No downloadable release yet/);
});

test('API URL supports same-origin and a configured host', () => {
  assert.equal(applicationsUrl(''), '/api/applications');
  assert.equal(
    applicationsUrl('https://api.example.com/'),
    'https://api.example.com/api/applications',
  );
});

test('fetch returns the aggregate contract and reports HTTP failures', async (t) => {
  let requestedUrl = '';
  t.mock.method(globalThis, 'fetch', async (url: string) => {
    requestedUrl = url;
    return new Response(JSON.stringify([application]), { status: 200 });
  });
  assert.deepEqual(await fetchApplications('https://api.example.com'), [
    application,
  ]);
  assert.equal(requestedUrl, 'https://api.example.com/api/applications');
  t.mock.restoreAll();
  t.mock.method(
    globalThis,
    'fetch',
    async () => new Response(null, { status: 503 }),
  );
  await assert.rejects(fetchApplications(''), /could not be loaded/);
});

test('gallery has accessible controls, lazy images and deterministic media ties', () => {
  const html = renderToStaticMarkup(
    <ApplicationCard
      application={{
        ...application,
        media: [
          { id: 'b', url: '/b.png', display_order: 0 },
          { id: 'a', url: '/a.png', display_order: 0 },
        ],
      }}
    />,
  );
  assert.ok(html.indexOf('/a.png') < html.indexOf('/b.png'));
  assert.match(html, /loading="lazy"/);
  assert.match(html, /decoding="async"/);
  assert.match(html, /tabindex="0"/);
  assert.match(
    html,
    /aria-label="Previous Test application preview"[^>]*disabled/,
  );
  assert.match(html, /aria-label="Next Test application preview"/);
  assert.match(html, /aria-controls=/);
});

test('a single missing media URL gets a fallback without navigation buttons', () => {
  const html = renderToStaticMarkup(
    <ApplicationCard
      application={{
        ...application,
        media: [{ id: 'missing', url: '', display_order: 0 }],
      }}
    />,
  );
  assert.match(html, /Preview unavailable/);
  assert.match(html, /preview 1 unavailable/);
  assert.doesNotMatch(html, /<img|<button/);
});
