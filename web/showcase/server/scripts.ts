import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { databaseConfig } from './config.ts';

export async function executeSql(file: 'schema' | 'seed') {
  const client = new pg.Client(databaseConfig());
  try {
    await client.connect();
    await client.query('BEGIN');
    await client.query(
      await readFile(new URL(`../db/${file}.sql`, import.meta.url), 'utf8'),
    );
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    await client.end();
  }
}
