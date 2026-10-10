import session from "express-session";
import connectPgSimple from "connect-pg-simple";
import { Pool, type PoolConfig } from "pg";

const PgStore = connectPgSimple(session);

export async function createSessionStore(options: PoolConfig) {
  const pool = new Pool({ ...options, max: 5, connectionTimeoutMillis: 5000 });
  pool.on("error", () => console.error("Admin session database connection failed."));
  const store = new PgStore({
    pool, schemaName: "admin_auth", tableName: "session",
    createTableIfMissing: true, disableTouch: true,
    errorLog: () => console.error("Admin session storage failed."),
  });
  try {
    // Infrastructure only: application schemas and tables are never modified.
    await pool.query('CREATE SCHEMA IF NOT EXISTS admin_auth');
    await pool.query('REVOKE ALL ON SCHEMA admin_auth FROM PUBLIC');
    // Force table initialization now so a bad connection/permission fails startup.
    await new Promise<void>((resolve, reject) => {
      store.get("startup-check", (error) => error ? reject(error) : resolve());
    });
    await pool.query('REVOKE ALL ON TABLE admin_auth.session FROM PUBLIC');
  } catch {
    store.close();
    await pool.end();
    throw new Error("Unable to initialize Admin session storage.");
  }
  return { store, close: async () => { store.close(); await pool.end(); } };
}
