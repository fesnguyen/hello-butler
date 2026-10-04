import pg from 'pg';
import { databaseConfig } from './config.ts';
import { listApplications } from './data.ts';
import { showcaseServer } from './http.ts';

const pool = new pg.Pool(databaseConfig());
pool.on('error', () => console.error('Showcase database connection failed'));
const server = showcaseServer(() => listApplications(pool));
const port = Number(process.env.SHOWCASE_HTTP_PORT || 8001);
if (!Number.isInteger(port) || port < 1 || port > 65535)
  throw new Error('Invalid SHOWCASE_HTTP_PORT');
server.listen(port, process.env.SHOWCASE_HTTP_HOST || '127.0.0.1', () =>
  console.log(`Showcase listening on port ${port}`),
);
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, () => {
    server.close(() => {
      void pool.end();
    });
  });
}
