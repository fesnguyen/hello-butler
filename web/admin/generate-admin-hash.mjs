import { randomBytes, scryptSync } from "node:crypto";
import { createInterface } from "node:readline";

const rl = createInterface({
  input: process.stdin,
  output: process.stdout,
});

rl.question("Enter admin password: ", (password) => {
  rl.close();

  if (!password) {
    console.error("Password cannot be empty.");
    process.exitCode = 1;
    return;
  }

  const salt = randomBytes(16);
  const hash = scryptSync(password, salt, 64);

  console.log(
    `ADMIN_PASSWORD_HASH=scrypt:${salt.toString("hex")}:${hash.toString("hex")}`
  );
});
