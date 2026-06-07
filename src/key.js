import { createHash } from "node:crypto";

export function deriveSpaceKey(secret) {
  if (!secret || secret.length < 8) {
    throw new Error("CLIP_FLOW_SECRET must be at least 8 characters for the local MVP");
  }

  return createHash("sha256").update(secret, "utf8").digest();
}
