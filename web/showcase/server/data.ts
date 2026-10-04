import type { Pool } from 'pg';
import type { ShowcaseApplication } from '../src/api.ts';

// Aggregation avoids per-application queries and excludes private credentials.
export const applicationsSql = `
SELECT a.id, a.name, a.description, a.github_url,
  COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'id', m.id, 'url', m.url, 'display_order', m.display_order)
    ORDER BY m.display_order, m.id)
    FROM media m WHERE m.application_id = a.id), '[]'::jsonb) AS media,
  (SELECT jsonb_build_object('version', r.version, 'download_url', r.download_url,
    'published_at', r.published_at)
    FROM releases r WHERE r.application_id = a.id AND r.published_at <= now()
    ORDER BY r.published_at DESC, r.id DESC LIMIT 1) AS latest_release
FROM applications a ORDER BY a.name, a.id`;

export async function listApplications(
  pool: Pick<Pool, 'query'>,
): Promise<ShowcaseApplication[]> {
  const result = await pool.query<ShowcaseApplication>(applicationsSql);
  return result.rows;
}
