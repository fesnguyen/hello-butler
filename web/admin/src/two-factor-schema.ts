// Private infrastructure only; existing content and session tables are untouched.
export const twoFactorSchema = `
CREATE SCHEMA IF NOT EXISTS admin_auth;
REVOKE ALL ON SCHEMA admin_auth FROM PUBLIC;
CREATE TABLE IF NOT EXISTS admin_auth.account (
  id text PRIMARY KEY CHECK (id = 'primary'),
  email text NOT NULL,
  encrypted_secret text,
  activated_at bigint,
  last_step bigint,
  version integer NOT NULL DEFAULT 1,
  setup_hash text,
  setup_expires_at bigint,
  password_failures integer NOT NULL DEFAULT 0,
  password_window_start bigint NOT NULL DEFAULT 0,
  password_locked_until bigint NOT NULL DEFAULT 0,
  factor_failures integer NOT NULL DEFAULT 0,
  factor_window_start bigint NOT NULL DEFAULT 0,
  factor_locked_until bigint NOT NULL DEFAULT 0,
  updated_at bigint NOT NULL,
  CHECK ((encrypted_secret IS NULL) = (activated_at IS NULL))
);
CREATE TABLE IF NOT EXISTS admin_auth.challenge (
  id text PRIMARY KEY,
  account_id text NOT NULL REFERENCES admin_auth.account(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('setup', 'enroll', 'login', 'replace')),
  version integer NOT NULL,
  expires_at bigint NOT NULL,
  encrypted_secret text,
  attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS admin_challenge_expiry ON admin_auth.challenge(expires_at);
CREATE TABLE IF NOT EXISTS admin_auth.recovery_code (
  account_id text NOT NULL REFERENCES admin_auth.account(id) ON DELETE CASCADE,
  hash text NOT NULL,
  created_at bigint NOT NULL,
  PRIMARY KEY (account_id, hash)
);
REVOKE ALL ON TABLE admin_auth.account, admin_auth.challenge, admin_auth.recovery_code FROM PUBLIC;
`;
