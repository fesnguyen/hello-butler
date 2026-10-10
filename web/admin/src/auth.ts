
import {
  scrypt as scryptCallback,
  timingSafeEqual,
} from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCallback);

export interface AdminIdentity {
  email: string;
}

export async function authenticateAdmin(
  email: string,
  password: string,
): Promise<AdminIdentity | null> {
  const configuredEmail = process.env.ADMIN_EMAIL;
  const storedHash = process.env.ADMIN_PASSWORD_HASH;

  if (!configuredEmail || !storedHash) {
    throw new Error("Admin authentication is not configured.");
  }

  const parts = storedHash.split(":");

  if (parts.length !== 3 || parts[0] !== "scrypt") {
    throw new Error("Invalid admin password hash configuration.");
  }

  const [, saltHex, hashHex] = parts;

  if (
    !/^[0-9a-f]{32}$/i.test(saltHex) ||
    !/^[0-9a-f]{128}$/i.test(hashHex)
  ) {
    throw new Error("Invalid admin password hash configuration.");
  }

  const salt = Buffer.from(saltHex, "hex");
  const expectedHash = Buffer.from(hashHex, "hex");

  const derivedHash = (await scrypt(
    password,
    salt,
    expectedHash.length,
  )) as Buffer;

  const passwordMatches = timingSafeEqual(
    derivedHash,
    expectedHash,
  );

  const emailMatches =
    email.trim().toLowerCase() ===
    configuredEmail.trim().toLowerCase();

  if (!passwordMatches || !emailMatches) {
    return null;
  }

  return {
    email: configuredEmail,
  };
}
