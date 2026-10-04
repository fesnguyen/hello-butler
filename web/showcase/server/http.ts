import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
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
) {
  return createServer(async (request, response) => {
    try {
      const path = new URL(request.url || '/', 'http://localhost').pathname;
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        response.writeHead(405, { Allow: 'GET, HEAD' }).end();
        return;
      }
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
      if (path.startsWith('/api/')) {
        response.writeHead(404).end();
        return;
      }
      const file = resolve(
        dist,
        `.${decodeURIComponent(path === '/' ? '/index.html' : path)}`,
      );
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
