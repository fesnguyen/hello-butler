import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { generateSecret, generateURI, verify } from "otplib";
import { crypto } from "@otplib/plugin-crypto-node";

export function digest(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

export function equalToken(value: unknown, expected: string): boolean {
  if (typeof value !== "string") return false;
  const actual = Buffer.from(value);
  const wanted = Buffer.from(expected);
  return actual.length === wanted.length && timingSafeEqual(actual, wanted);
}

export function encryptSecret(secret: string, key: Buffer, context: string) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(`hello-butler-admin:${context}`));
  const ciphertext = Buffer.concat([cipher.update(secret, "utf8"), cipher.final()]);
  return ["v1", iv.toString("hex"), cipher.getAuthTag().toString("hex"), ciphertext.toString("hex")].join(":");
}

export function decryptSecret(value: string, key: Buffer, context: string) {
  const [version, iv, tag, ciphertext] = value.split(":");
  if (
    version !== "v1" ||
    !/^[0-9a-f]{24}$/.test(iv ?? "") ||
    !/^[0-9a-f]{32}$/.test(tag ?? "") ||
    !/^(?:[0-9a-f]{2})+$/.test(ciphertext ?? "") ||
    value.split(":").length !== 4
  ) {
    throw new Error("Invalid encrypted authenticator state.");
  }
  const decipher = createDecipheriv("aes-256-gcm", key, Buffer.from(iv, "hex"));
  decipher.setAAD(Buffer.from(`hello-butler-admin:${context}`));
  decipher.setAuthTag(Buffer.from(tag, "hex"));
  return Buffer.concat([decipher.update(Buffer.from(ciphertext, "hex")), decipher.final()]).toString("utf8");
}

export function newSecret() {
  return generateSecret({ length: 20, crypto });
}
export function authenticatorUri(secret: string, email: string) {
  return generateURI({
    issuer: "Hello Butler Admin",
    label: email,
    secret,
    algorithm: "sha1",
    digits: 6,
    period: 30,
  });
}

// One previous 30-second step, no future steps. Persist the matched step atomically.
export async function acceptedStep(secret: string, code: unknown, now: number, lastStep: number | null) {
  if (typeof code !== "string" || !/^\d{6}$/.test(code)) return null;
  const result = await verify({
    secret,
    token: code,
    crypto,
    algorithm: "sha1",
    digits: 6,
    period: 30,
    epoch: Math.floor(now / 1000),
    epochTolerance: [30, 0],
    ...(lastStep === null ? {} : { afterTimeStep: lastStep }),
  });
  return result.valid && "timeStep" in result ? result.timeStep : null;
}

export function newRecoveryCodes() {
  return Array.from({ length: 10 }, () => randomBytes(16).toString("hex").match(/.{8}/g)!.join("-"));
}
export function recoveryDigest(value: unknown) {
  if (typeof value !== "string" || !/^[0-9a-f]{8}(?:-[0-9a-f]{8}){3}$/i.test(value.trim())) return null;
  return digest(`recovery:${value.trim().toLowerCase()}`);
}
