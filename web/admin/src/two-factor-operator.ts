import { Pool } from "pg";
import { readAdminConfig } from "./config.js";
import { databaseOptions } from "./database.js";
import { TwoFactorStore } from "./two-factor.js";

async function run() {
  const [command, confirmation] = process.argv.slice(2);
  if (
    !["upgrade", "authorize", "reset"].includes(command) ||
    (command === "reset" && confirmation !== "--confirm-reset") ||
    (command !== "reset" && confirmation !== undefined) ||
    process.argv.length > 4
  ) {
    throw new Error("Use upgrade, authorize, or reset --confirm-reset.");
  }
  const config = readAdminConfig();
  const pool = new Pool(
    databaseOptions("SHOWCASE_DB", "SHOWCASE_USER", "SHOWCASE_PASSWORD", "SHOWCASE_HOST", "SHOWCASE_PORT"),
  );
  pool.on("error", () => console.error("Admin operator database connection failed."));
  try {
    const factors = new TwoFactorStore(pool, config);
    await factors.initialize(command !== "reset");
    if (command === "authorize") {
      const token = await factors.authorizeOperator();
      // Explicit local credential delivery, not an application log. Never emit TOTP/recovery secrets.
      process.stdout.write(`Single-use setup token (expires in 10 minutes):\n${token}\n`);
    } else if (command === "reset") {
      await factors.emergencyReset();
      process.stdout.write("Authenticator reset. Sessions are revoked. Run authorize to allow new enrollment.\n");
    } else process.stdout.write("Admin two-factor storage upgraded without resetting existing data.\n");
  } finally {
    await pool.end();
  }
}
run().catch(() => {
  console.error("Admin operator command failed. Check command, configuration, account and database access.");
  process.exitCode = 1;
});
