import type { PoolConfig } from 'pg';

export function databaseConfig(
  env: NodeJS.ProcessEnv = process.env,
): PoolConfig {
  const required = (key: string) => {
    const value = env[key];
    if (!value) throw new Error(`${key} is required`);
    return value;
  };
  const database = required('SHOWCASE_DB');
  const user = required('SHOWCASE_USER');
  if (database === env.POSTGRES_DB || user === env.POSTGRES_USER) {
    throw new Error('Showcase database and user must differ from Butler');
  }
  if (!/^showcase(?:_[a-z0-9_]+)?$/.test(database)) {
    throw new Error('SHOWCASE_DB must be showcase or showcase_<environment>');
  }
  const port = Number(env.SHOWCASE_PORT || 5433);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error('Invalid SHOWCASE_PORT');
  return {
    database,
    user,
    password: required('SHOWCASE_PASSWORD'),
    host: env.SHOWCASE_HOST || 'localhost',
    port,
    max: 5,
    connectionTimeoutMillis: 5000,
    statement_timeout: 10000,
  };
}
