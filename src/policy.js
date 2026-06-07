import { ContentType, DeliveryMode, Sensitivity } from "./protocol.js";

const DEFAULT_TEXT_LIMIT_BYTES = 256 * 1024;
const DEFAULT_IMAGE_LIMIT_BYTES = 5 * 1024 * 1024;

const SENSITIVE_PATTERNS = [
  /\b\d{6}\b/u,
  /\b(?:api[_-]?key|access[_-]?token|secret|password|passwd)\b\s*[:=]/iu,
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/u,
  /\b[A-Za-z0-9_-]{32,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\b/u
];

export function classifyText(value) {
  if (/^https?:\/\/\S+$/iu.test(value.trim())) {
    return ContentType.URL;
  }

  return ContentType.TEXT;
}

export function detectSensitivity({ contentType, payload, sourceApp = "" }) {
  const blockedSource = /password|1password|bitwarden|authenticator|bank|wallet/iu.test(sourceApp);
  if (blockedSource) {
    return Sensitivity.SENSITIVE;
  }

  if (contentType !== ContentType.TEXT && contentType !== ContentType.URL) {
    return Sensitivity.NORMAL;
  }

  return SENSITIVE_PATTERNS.some((pattern) => pattern.test(payload))
    ? Sensitivity.SENSITIVE
    : Sensitivity.NORMAL;
}

export function decideDelivery(clip, settings = {}) {
  const textLimit = settings.textLimitBytes ?? DEFAULT_TEXT_LIMIT_BYTES;
  const imageLimit = settings.imageLimitBytes ?? DEFAULT_IMAGE_LIMIT_BYTES;

  if (clip.sensitivity === Sensitivity.SENSITIVE) {
    return DeliveryMode.OFFER;
  }

  if (clip.contentType === ContentType.TEXT || clip.contentType === ContentType.URL) {
    return clip.size <= textLimit ? DeliveryMode.AUTO : DeliveryMode.OFFER;
  }

  if (clip.contentType === ContentType.IMAGE) {
    return clip.size <= imageLimit && settings.imageSyncEnabled !== false
      ? DeliveryMode.AUTO
      : DeliveryMode.OFFER;
  }

  if (clip.contentType === ContentType.FILE) {
    return DeliveryMode.MANUAL;
  }

  return DeliveryMode.OFFER;
}
