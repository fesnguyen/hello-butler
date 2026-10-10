
import AdminJS from "adminjs";
import { Adapter, Database, Resource } from "@adminjs/sql";

AdminJS.registerAdapter({ Database, Resource });

function requiredEnv(name: string): string {
  const value = process.env[name];

  if (!value) {
    throw new Error(`Missing environment variable: ${name}`);
  }

  return value;
}

async function connectDatabase(
  databaseEnv: string,
  userEnv: string,
  passwordEnv: string,
  hostEnv: string,
  portEnv: string,
) {
  const database = requiredEnv(databaseEnv);
  const user = requiredEnv(userEnv);
  const password = requiredEnv(passwordEnv);
  const host = process.env[hostEnv] || "localhost";
  const port = Number(process.env[portEnv] || 5433);

  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Invalid database port: ${portEnv}`);
  }

  const connectionUrl = new URL("postgresql://localhost");
  connectionUrl.hostname = host;
  connectionUrl.port = String(port);
  connectionUrl.username = user;
  connectionUrl.password = password;
  connectionUrl.pathname = `/${database}`;

  const adapter = new Adapter("postgresql", {
    connectionString: connectionUrl.toString(),
    database,
  });

  return adapter.init();
}

export async function initializeDatabases() {
  const helloButler = await connectDatabase(
    "POSTGRES_DB",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "POSTGRES_HOST",
    "POSTGRES_PORT",
  );

  const showcase = await connectDatabase(
    "SHOWCASE_DB",
    "SHOWCASE_USER",
    "SHOWCASE_PASSWORD",
    "SHOWCASE_HOST",
    "SHOWCASE_PORT",
  );

  return [helloButler, showcase];
}
