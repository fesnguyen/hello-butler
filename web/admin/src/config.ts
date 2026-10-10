export interface AdminConfig {
  email: string;
  passwordHash: string;
  sessionSecret: string;
  secureCookies: boolean;
  trustProxy: false | "loopback";
}

export const SESSION_MAX_AGE = 8 * 60 * 60 * 1000;
export const COOKIE_NAME = "hello-butler-admin";
export const COOKIE_PATH = "/admin";

export function readAdminConfig(env: NodeJS.ProcessEnv = process.env): AdminConfig {
  if (env.NODE_ENV && !["development", "test", "production"].includes(env.NODE_ENV)) {
    throw new Error("NODE_ENV must be development, test, or production.");
  }
  const email = env.ADMIN_EMAIL?.trim();
  if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new Error("ADMIN_EMAIL must be a valid email address.");
  }
  const passwordHash = env.ADMIN_PASSWORD_HASH ?? "";
  if (!/^scrypt:[0-9a-f]{32}:[0-9a-f]{128}$/i.test(passwordHash)) {
    throw new Error("ADMIN_PASSWORD_HASH must contain a valid scrypt hash.");
  }
  const sessionSecret = env.ADMIN_SESSION_SECRET ?? "";
  if (sessionSecret.trim().length < 32) {
    throw new Error("ADMIN_SESSION_SECRET must contain at least 32 characters.");
  }
  if (env.ADMIN_TRUST_PROXY && env.ADMIN_TRUST_PROXY !== "loopback") {
    throw new Error("ADMIN_TRUST_PROXY must be unset or loopback.");
  }
  if (
    env.ADMIN_LOCAL_HTTP === "true" &&
    env.NODE_ENV !== "production"
  ) {
    throw new Error("ADMIN_LOCAL_HTTP is only intended for production.");
  }
  return {
    email, passwordHash, sessionSecret,
    secureCookies:
      env.NODE_ENV === "production" &&
      env.ADMIN_LOCAL_HTTP !== "true",
    trustProxy: env.ADMIN_TRUST_PROXY === "loopback" ? "loopback" : false,
  };
}
