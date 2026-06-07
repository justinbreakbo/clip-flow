import { randomUUID } from "node:crypto";

export const ContentType = Object.freeze({
  TEXT: "text",
  URL: "url",
  IMAGE: "image",
  FILE: "file",
  UNKNOWN: "unknown"
});

export const DeliveryMode = Object.freeze({
  AUTO: "auto",
  OFFER: "offer",
  MANUAL: "manual",
  BLOCKED: "blocked"
});

export const Sensitivity = Object.freeze({
  NORMAL: "normal",
  SENSITIVE: "sensitive"
});

export function createClip({
  spaceId,
  sourceDeviceId,
  contentType,
  payload,
  metadata = {},
  sensitivity = Sensitivity.NORMAL,
  deliveryMode = DeliveryMode.OFFER,
  now = new Date()
}) {
  const createdAt = now.toISOString();
  const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();

  return {
    id: randomUUID(),
    spaceId,
    sourceDeviceId,
    createdAt,
    expiresAt,
    contentType,
    size: Buffer.byteLength(payload, "utf8"),
    metadata,
    sensitivity,
    deliveryMode,
    payload
  };
}

export function createOffer(clip, targetDeviceId) {
  return {
    type: "clip.offer",
    messageId: randomUUID(),
    clipId: clip.id,
    spaceId: clip.spaceId,
    sourceDeviceId: clip.sourceDeviceId,
    targetDeviceId,
    contentType: clip.contentType,
    size: clip.size,
    sensitivity: clip.sensitivity,
    deliveryMode: clip.deliveryMode,
    createdAt: clip.createdAt,
    expiresAt: clip.expiresAt,
    metadata: clip.metadata
  };
}

export function createPayloadMessage(clip, encryptedPayload) {
  return {
    type: "clip.payload",
    messageId: randomUUID(),
    clipId: clip.id,
    spaceId: clip.spaceId,
    sourceDeviceId: clip.sourceDeviceId,
    contentType: clip.contentType,
    encryptedPayload,
    createdAt: clip.createdAt,
    expiresAt: clip.expiresAt
  };
}

export function isExpired(entity, now = new Date()) {
  return new Date(entity.expiresAt).getTime() <= now.getTime();
}
