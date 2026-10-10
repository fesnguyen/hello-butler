import { scrypt as scryptCallback, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
import { readAdminConfig, type AdminConfig } from "./config.js";

const scrypt = promisify(scryptCallback);
export interface AdminIdentity { email: string }

export function createAuthenticator(config: AdminConfig) {
  const [, saltHex, hashHex] = config.passwordHash.split(":");
  const salt = Buffer.from(saltHex, "hex");
  const expectedHash = Buffer.from(hashHex, "hex");
  return async (email: unknown, password: unknown): Promise<AdminIdentity | null> => {
    if (typeof email !== "string" || typeof password !== "string" ||
        email.length === 0 || email.length > 254 || password.length === 0 ||
        Buffer.byteLength(password, "utf8") > 1024) return null;
    // Verify even for a different email; never disclose which credential failed.
    const derived = await scrypt(password, salt, expectedHash.length) as Buffer;
    const matches = timingSafeEqual(derived, expectedHash);
    if (!matches || email.trim().toLowerCase() !== config.email.toLowerCase()) return null;
    return { email: config.email };
  };
}

// Retain the existing entry point for callers outside the router factory.
export async function authenticateAdmin(email: string, password: string): Promise<AdminIdentity | null> {
  return createAuthenticator(readAdminConfig())(email, password);
}
