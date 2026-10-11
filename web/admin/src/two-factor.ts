import { randomBytes } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import type { AdminConfig } from "./config.js";
import { PENDING_MAX_AGE, SETUP_MAX_AGE } from "./config.js";
import {
  acceptedStep,
  authenticatorUri,
  decryptSecret,
  digest,
  encryptSecret,
  equalToken,
  newRecoveryCodes,
  newSecret,
  recoveryDigest,
} from "./totp.js";
import { twoFactorSchema } from "./two-factor-schema.js";

type Channel = "password" | "factor";
export type FactorKind = "totp" | "recovery";
interface Account {
  email: string;
  encrypted_secret: string | null;
  version: number;
  last_step: string | null;
  setup_hash: string | null;
  setup_expires_at: string | null;
  password_failures: number;
  password_window_start: string;
  password_locked_until: string;
  factor_failures: number;
  factor_window_start: string;
  factor_locked_until: string;
}
export interface Challenge {
  id: string;
  kind: "setup" | "enroll" | "login" | "replace";
  version: number;
  expires_at: string;
  encrypted_secret: string | null;
  attempts: number;
}
export class AuthenticationLocked extends Error {
  constructor() {
    super("Unable to verify authentication. Try again later.");
  }
}
const LOCK_WINDOW = 15 * 60 * 1000;

export class TwoFactorStore {
  constructor(
    private pool: Pool,
    private config: AdminConfig,
    readonly now: () => number = Date.now,
  ) {}

  async initialize(verifyKey = true) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // Coordinate first startup/explicit upgrades without resetting existing state.
      await client.query("SELECT pg_advisory_xact_lock(72419011)");
      await client.query(twoFactorSchema);
      await client.query(
        "INSERT INTO admin_auth.account(id,email,updated_at) VALUES ('primary',$1,$2) ON CONFLICT(id) DO NOTHING",
        [this.config.email.toLowerCase(), this.now()],
      );
      const account = (await client.query<Account>("SELECT * FROM admin_auth.account WHERE id='primary'")).rows[0];
      if (account.email !== this.config.email.toLowerCase())
        throw new Error("Configured administrator does not match enrolled account.");
      if (verifyKey && account.encrypted_secret)
        decryptSecret(account.encrypted_secret, this.config.totpEncryptionKey, "primary");
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  private async transaction<T>(fn: (client: PoolClient, account: Account) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // All secret changes, attempts, replay checks and code consumption serialize here.
      const account = (await client.query<Account>("SELECT * FROM admin_auth.account WHERE id='primary' FOR UPDATE"))
        .rows[0];
      if (!account || account.email !== this.config.email.toLowerCase())
        throw new Error("Invalid administrator state.");
      const result = await fn(client, account);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  private checkLock(account: Account, channel: Channel) {
    if (Number(account[`${channel}_locked_until`]) > this.now()) throw new AuthenticationLocked();
  }
  private async failure(client: PoolClient, account: Account, channel: Channel) {
    const start = Number(account[`${channel}_window_start`]);
    const count = this.now() - start >= LOCK_WINDOW ? 1 : account[`${channel}_failures`] + 1;
    await client.query(
      `UPDATE admin_auth.account SET ${channel}_failures=$1, ${channel}_window_start=$2, ${channel}_locked_until=$3 WHERE id='primary'`,
      [count, this.now() - start >= LOCK_WINDOW ? this.now() : start, count >= 10 ? this.now() + LOCK_WINDOW : 0],
    );
  }
  private async clearFailures(client: PoolClient, channel: Channel) {
    await client.query(
      `UPDATE admin_auth.account SET ${channel}_failures=0, ${channel}_window_start=0, ${channel}_locked_until=0 WHERE id='primary'`,
    );
  }

  async passwordAllowed() {
    return this.transaction(async (_client, account) => {
      this.checkLock(account, "password");
    });
  }
  async passwordResult(valid: boolean) {
    return this.transaction(async (client, account) => {
      this.checkLock(account, "password");
      if (valid) await this.clearFailures(client, "password");
      else await this.failure(client, account, "password");
    });
  }
  async status() {
    const result = await this.pool.query<{
      email: string;
      version: number;
      active: boolean;
    }>("SELECT email, version, encrypted_secret IS NOT NULL AS active FROM admin_auth.account WHERE id='primary'");
    const state = result.rows[0];
    if (!state || state.email !== this.config.email.toLowerCase()) throw new Error("Invalid administrator state.");
    return state;
  }
  private async insertChallenge(client: PoolClient, account: Account, kind: Challenge["kind"], secret?: string) {
    const id = randomBytes(32).toString("hex");
    await client.query("DELETE FROM admin_auth.challenge WHERE expires_at <= $1", [this.now()]);
    const result = await client.query<Challenge>(
      `INSERT INTO admin_auth.challenge(id,account_id,kind,version,expires_at,encrypted_secret)
      VALUES ($1,'primary',$2,$3,$4,$5) RETURNING *`,
      [
        id,
        kind,
        account.version,
        this.now() + PENDING_MAX_AGE,
        secret ? encryptSecret(secret, this.config.totpEncryptionKey, id) : null,
      ],
    );
    return result.rows[0];
  }
  async beginLogin() {
    return this.transaction(async (client, account) =>
      this.insertChallenge(client, account, account.encrypted_secret ? "login" : "setup"),
    );
  }
  private async challenge(client: PoolClient, account: Account, id: string, kinds: Challenge["kind"][]) {
    const item = (
      await client.query<Challenge>("SELECT * FROM admin_auth.challenge WHERE id=$1 AND account_id='primary'", [id])
    ).rows[0];
    if (
      !item ||
      item.version !== account.version ||
      Number(item.expires_at) <= this.now() ||
      item.attempts >= 5 ||
      !kinds.includes(item.kind)
    )
      return null;
    return item;
  }
  async cancel(id: string) {
    await this.pool.query("DELETE FROM admin_auth.challenge WHERE id=$1", [id]);
  }

  async authorizeSetup(id: string, token: unknown) {
    return this.transaction(async (client, account) => {
      this.checkLock(account, "factor");
      const item = await this.challenge(client, account, id, ["setup"]);
      if (!item || account.encrypted_secret) return false;
      if (
        typeof token !== "string" ||
        !/^[0-9a-f]{64}$/.test(token) ||
        !account.setup_hash ||
        Number(account.setup_expires_at) <= this.now() ||
        !equalToken(digest(token), account.setup_hash)
      ) {
        await this.rejectChallenge(client, account, item);
        return false;
      }
      const secret = newSecret();
      await client.query("UPDATE admin_auth.account SET setup_hash=NULL, setup_expires_at=NULL WHERE id='primary'");
      await client.query("UPDATE admin_auth.challenge SET kind='enroll', encrypted_secret=$2 WHERE id=$1", [
        id,
        encryptSecret(secret, this.config.totpEncryptionKey, id),
      ]);
      return true;
    });
  }
  async preview(id: string) {
    return this.transaction(async (client, account) => {
      const item = await this.challenge(client, account, id, ["enroll", "replace"]);
      if (!item?.encrypted_secret) return null;
      const secret = decryptSecret(item.encrypted_secret, this.config.totpEncryptionKey, item.id);
      return authenticatorUri(secret, this.config.email);
    });
  }
  private async rejectChallenge(client: PoolClient, account: Account, item: Challenge) {
    await this.failure(client, account, "factor");
    await client.query("UPDATE admin_auth.challenge SET attempts=attempts+1 WHERE id=$1", [item.id]);
  }
  private async consumeFactor(client: PoolClient, account: Account, code: unknown, kind: FactorKind) {
    this.checkLock(account, "factor");
    if (!account.encrypted_secret) return false;
    if (kind === "recovery") {
      const hash = recoveryDigest(code);
      if (!hash) return false;
      return (
        (
          await client.query(
            "DELETE FROM admin_auth.recovery_code WHERE account_id='primary' AND hash=$1 RETURNING hash",
            [hash],
          )
        ).rowCount === 1
      );
    }
    const secret = decryptSecret(account.encrypted_secret, this.config.totpEncryptionKey, "primary");
    const step = await acceptedStep(
      secret,
      code,
      this.now(),
      account.last_step === null ? null : Number(account.last_step),
    );
    if (step === null) return false;
    await client.query("UPDATE admin_auth.account SET last_step=$1, updated_at=$2 WHERE id='primary'", [
      step,
      this.now(),
    ]);
    return true;
  }
  async verifyLogin(id: string, code: unknown, kind: FactorKind) {
    return this.transaction(async (client, account) => {
      this.checkLock(account, "factor");
      const item = await this.challenge(client, account, id, ["login"]);
      if (!item) return null;
      if (!(await this.consumeFactor(client, account, code, kind))) {
        await this.rejectChallenge(client, account, item);
        return null;
      }
      await this.clearFailures(client, "factor");
      await client.query("DELETE FROM admin_auth.challenge WHERE id=$1", [id]);
      return account.version;
    });
  }
  private async rotateRecovery(client: PoolClient) {
    const codes = newRecoveryCodes();
    await client.query("DELETE FROM admin_auth.recovery_code WHERE account_id='primary'");
    for (const code of codes)
      await client.query("INSERT INTO admin_auth.recovery_code(account_id,hash,created_at) VALUES ('primary',$1,$2)", [
        recoveryDigest(code),
        this.now(),
      ]);
    return codes;
  }
  async confirmEnrollment(id: string, code: unknown) {
    return this.transaction(async (client, account) => {
      this.checkLock(account, "factor");
      const item = await this.challenge(client, account, id, ["enroll", "replace"]);
      if (!item?.encrypted_secret || (item.kind === "enroll" && account.encrypted_secret)) return null;
      const secret = decryptSecret(item.encrypted_secret, this.config.totpEncryptionKey, id);
      const step = await acceptedStep(secret, code, this.now(), null);
      if (step === null) {
        await this.rejectChallenge(client, account, item);
        return null;
      }
      await client.query(
        `UPDATE admin_auth.account SET encrypted_secret=$1,activated_at=$2,last_step=$3,
        version=version+1,setup_hash=NULL,setup_expires_at=NULL,updated_at=$2 WHERE id='primary'`,
        [encryptSecret(secret, this.config.totpEncryptionKey, "primary"), this.now(), step],
      );
      const codes = await this.rotateRecovery(client);
      await this.clearFailures(client, "factor");
      await client.query("DELETE FROM admin_auth.challenge WHERE account_id='primary'");
      return { version: account.version + 1, codes };
    });
  }
  async manage(version: number, code: unknown, kind: FactorKind, operation: "replace" | "recovery") {
    return this.transaction(async (client, account) => {
      this.checkLock(account, "factor");
      if (account.version !== version || !account.encrypted_secret) return null;
      if (!(await this.consumeFactor(client, account, code, kind))) {
        await this.failure(client, account, "factor");
        return null;
      }
      await this.clearFailures(client, "factor");
      if (operation === "replace")
        return {
          challenge: await this.insertChallenge(client, account, "replace", newSecret()),
        };
      const codes = await this.rotateRecovery(client);
      await client.query("UPDATE admin_auth.account SET version=version+1,updated_at=$1 WHERE id='primary'", [
        this.now(),
      ]);
      await client.query("DELETE FROM admin_auth.challenge WHERE account_id='primary'");
      return { version: account.version + 1, codes };
    });
  }
  // Local operator commands only. There is no HTTP reset/authorization endpoint.
  async authorizeOperator() {
    return this.transaction(async (client, account) => {
      if (account.encrypted_secret) throw new Error("Authenticator is already enrolled. Use normal replacement.");
      const token = randomBytes(32).toString("hex");
      await client.query(
        "UPDATE admin_auth.account SET setup_hash=$1,setup_expires_at=$2,updated_at=$3 WHERE id='primary'",
        [digest(token), this.now() + SETUP_MAX_AGE, this.now()],
      );
      return token;
    });
  }
  async emergencyReset() {
    await this.transaction(async (client) => {
      await client.query(
        `UPDATE admin_auth.account SET encrypted_secret=NULL,activated_at=NULL,last_step=NULL,
        setup_hash=NULL,setup_expires_at=NULL,version=version+1,password_failures=0,password_window_start=0,
        password_locked_until=0,factor_failures=0,factor_window_start=0,factor_locked_until=0,updated_at=$1 WHERE id='primary'`,
        [this.now()],
      );
      await client.query("DELETE FROM admin_auth.challenge WHERE account_id='primary'");
      await client.query("DELETE FROM admin_auth.recovery_code WHERE account_id='primary'");
    });
  }
}
