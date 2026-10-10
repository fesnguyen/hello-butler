import AdminJS from "adminjs";
import { createAdminApp } from "./app.js";
import { readAdminConfig } from "./config.js";
import { databaseOptions, initializeDatabases } from "./database.js";
import { createSessionStore } from "./sessions.js";

async function start() {
  const config = readAdminConfig();
  const port = Number(process.env.PORT ?? 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid Admin PORT.");
  // Validate all connections before opening any of them.
  databaseOptions("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_HOST", "POSTGRES_PORT");
  const sessionOptions = databaseOptions("SHOWCASE_DB", "SHOWCASE_USER", "SHOWCASE_PASSWORD", "SHOWCASE_HOST", "SHOWCASE_PORT");
  const sessions = await createSessionStore(sessionOptions);
  const databases = await initializeDatabases();
  const admin = new AdminJS({ rootPath: "/admin", databases });
  const app = createAdminApp(admin, config, sessions.store);
  const server = app.listen(port, "127.0.0.1", () => {
    console.log(`AdminJS listening on 127.0.0.1:${port}/admin`);
  });
  server.on("error", () => { console.error("Unable to start Admin listener."); process.exit(1); });
  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.once(signal, () => {
      server.close(() => { void sessions.close().finally(() => process.exit(0)); });
    });
  }
}

start().catch(() => {
  // Connection and library errors may contain credentials: never print them.
  console.error("Admin startup failed. Check authentication and database configuration.");
  process.exit(1);
});
