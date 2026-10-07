import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, resolve, sep } from 'node:path';

import type { ShowcaseApplication } from '../src/api.ts';

const mime: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
};

export function showcaseServer(
  load: () => Promise<ShowcaseApplication[]>,
  dist = resolve('dist'),
  releases = resolve('releases'),
) {
  return createServer(async (request, response) => {
    try {
      const path = new URL(request.url || '/', 'http://localhost').pathname;

      // Showcase is read-only. Only GET and HEAD requests are supported.
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        response.writeHead(405, { Allow: 'GET, HEAD' }).end();
        return;
      }

      // Public Showcase data comes directly from the Showcase database.
      if (path === '/api/applications') {
        try {
          const body = JSON.stringify(await load());

          response.writeHead(200, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
          });
          response.end(request.method === 'HEAD' ? undefined : body);
        } catch (error) {
          console.error(
            'Showcase query failed',
            error instanceof Error ? error.name : 'Error',
          );

          response
            .writeHead(503, { 'Content-Type': 'application/json' })
            .end(JSON.stringify({ error: 'Collection unavailable' }));
        }

        return;
      }

      // Reject unknown API routes instead of falling through to static files.
      if (path.startsWith('/api/')) {
        response.writeHead(404).end();
        return;
      }

      // Published application releases are stored outside dist/.
      //
      // dist/ is disposable and replaced whenever Showcase is deployed.
      // releases/ is persistent and must survive Showcase deployments.
      //
      // /releases/hello-butler-v0.1.0.apk
      //      ->
      // ./releases/hello-butler-v0.1.0.apk
      if (path.startsWith('/releases/')) {
        const relativePath = decodeURIComponent(
          path.slice('/releases/'.length),
        );
        const file = resolve(releases, relativePath);

        // Prevent paths such as /releases/../../some-file from escaping
        // the persistent releases directory.
        if (!file.startsWith(releases + sep)) {
          response.writeHead(404).end();
          return;
        }

        try {
          const body = await readFile(file);

          response.writeHead(200, {
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Disposition': `attachment; filename="${relativePath}"`,
            'X-Content-Type-Options': 'nosniff',
          });
          response.end(request.method === 'HEAD' ? undefined : body);
        } catch {
          response.writeHead(404).end();
        }

        return;
      }

      // Everything else is a static Showcase frontend asset from dist/.
      // "/" resolves to the built React application's index.html.
      const file = resolve(
        dist,
        `.${decodeURIComponent(path === '/' ? '/index.html' : path)}`,
      );

      // Do not allow static paths to escape the dist directory.
      if (!file.startsWith(dist + sep)) {
        response.writeHead(404).end();
        return;
      }

      try {
        const body = await readFile(file);

        response.writeHead(200, {
          'Content-Type': mime[extname(file)] || 'application/octet-stream',
          'X-Content-Type-Options': 'nosniff',
        });
        response.end(request.method === 'HEAD' ? undefined : body);
      } catch {
        response.writeHead(404).end();
      }
    } catch {
      response.writeHead(400).end();
    }
  });
}