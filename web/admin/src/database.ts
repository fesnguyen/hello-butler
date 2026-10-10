
import AdminJS from "adminjs";
import { Adapter, Database, Resource } from "@adminjs/sql";

AdminJS.registerAdapter({ Database, Resource });

function toNodePostgresUrl(url: string): string {
  return url.replace(
    /^postgresql\+asyncpg:\/\//,
    "postgresql://",
  );
}

async function connectDatabase(
  envName: string,
  databaseName: string,
) {
  const url = process.env[envName];

  if (!url) {
    throw new Error(`Missing environment variable: ${envName}`);
  }

  const adapter = new Adapter("postgresql", {
    connectionString: toNodePostgresUrl(url),
    database: databaseName,
  });

  return await adapter.init();
}

export async function initializeDatabases() {
  const helloButler = await connectDatabase(
    "DATABASE_URL",
    "hello_butler_dev",
  );

  const showcase = await connectDatabase(
    "SHOWCASE_DATABASE_URL",
    "showcase_dev",
  );

  return [helloButler, showcase];
}
