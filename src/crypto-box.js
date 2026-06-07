import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const ALGORITHM = "aes-256-gcm";

export function createSpaceKey() {
  return randomBytes(32);
}

export function encryptPayload(spaceKey, payload) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv(ALGORITHM, spaceKey, nonce);
  const ciphertext = Buffer.concat([cipher.update(payload, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();

  return {
    algorithm: ALGORITHM,
    nonce: nonce.toString("base64url"),
    tag: tag.toString("base64url"),
    ciphertext: ciphertext.toString("base64url")
  };
}

export function decryptPayload(spaceKey, encryptedPayload) {
  const decipher = createDecipheriv(
    encryptedPayload.algorithm,
    spaceKey,
    Buffer.from(encryptedPayload.nonce, "base64url")
  );

  decipher.setAuthTag(Buffer.from(encryptedPayload.tag, "base64url"));

  return Buffer.concat([
    decipher.update(Buffer.from(encryptedPayload.ciphertext, "base64url")),
    decipher.final()
  ]).toString("utf8");
}
