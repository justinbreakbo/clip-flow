import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { encryptPayload, decryptPayload } from "./crypto-box.js";
import { deriveSpaceKey } from "./key.js";
import { classifyText, decideDelivery, detectSensitivity } from "./policy.js";
import { createClip, createOffer, createPayloadMessage } from "./protocol.js";

export class ClipClient {
  constructor({
    relayUrl = "http://localhost:42820",
    secret = "local-dev-secret",
    deviceName,
    platform,
    dataDir = join(process.cwd(), ".clip-flow")
  }) {
    this.relayUrl = relayUrl;
    this.spaceKey = deriveSpaceKey(secret);
    this.deviceName = deviceName;
    this.platform = platform;
    this.dataDir = dataDir;
    this.deviceFile = join(dataDir, `${deviceName.replace(/[^a-z0-9_-]/giu, "_")}.json`);
    this.deviceId = this.loadDeviceId();
  }

  loadDeviceId() {
    if (!existsSync(this.dataDir)) {
      mkdirSync(this.dataDir, { recursive: true });
    }

    if (existsSync(this.deviceFile)) {
      return JSON.parse(readFileSync(this.deviceFile, "utf8")).deviceId;
    }

    const deviceId = randomUUID();
    writeFileSync(this.deviceFile, JSON.stringify({ deviceId }, null, 2));
    return deviceId;
  }

  async postJson(path, body) {
    const response = await fetch(`${this.relayUrl}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });

    if (!response.ok) {
      throw new Error(`${path} failed: ${response.status}`);
    }

    return response.json();
  }

  async getJson(path) {
    const response = await fetch(`${this.relayUrl}${path}`);

    if (!response.ok) {
      throw new Error(`${path} failed: ${response.status}`);
    }

    return response.json();
  }

  async register() {
    return this.postJson("/register", {
      deviceId: this.deviceId,
      name: this.deviceName,
      platform: this.platform
    });
  }

  buildClip(value, options = {}) {
    const contentType = classifyText(value);
    const sensitivity = detectSensitivity({
      contentType,
      payload: value,
      sourceApp: options.sourceApp
    });
    const initialClip = createClip({
      spaceId: "local-space",
      sourceDeviceId: this.deviceId,
      contentType,
      payload: value,
      sensitivity
    });
    const deliveryMode = options.forceOffer ? "offer" : decideDelivery(initialClip, options.settings);

    return {
      ...initialClip,
      deliveryMode
    };
  }

  async sendText(value, options = {}) {
    const clip = this.buildClip(value, options);
    const encryptedPayload = encryptPayload(this.spaceKey, value);
    const targetDeviceId = options.targetDeviceId ?? null;

    const result = await this.postJson("/clips", {
      sourceDeviceId: this.deviceId,
      targetDeviceId,
      offer: createOffer(clip, targetDeviceId),
      payload: clip.deliveryMode === "auto" ? createPayloadMessage(clip, encryptedPayload) : null,
      withheldPayload: clip.deliveryMode === "auto" ? null : createPayloadMessage(clip, encryptedPayload)
    });

    return {
      result,
      clip
    };
  }

  async poll({ timeoutMs = 0 } = {}) {
    return this.getJson(`/poll?deviceId=${encodeURIComponent(this.deviceId)}&timeoutMs=${timeoutMs}`);
  }

  async fetchWithheldPayload(messageId) {
    return this.postJson("/payloads/fetch", {
      deviceId: this.deviceId,
      messageId
    });
  }

  decryptMessage(message) {
    if (!message.payload?.encryptedPayload) {
      return null;
    }

    return decryptPayload(this.spaceKey, message.payload.encryptedPayload);
  }

  async acceptOffer(message) {
    if (!message.hasWithheldPayload) {
      return null;
    }

    const fetched = await this.fetchWithheldPayload(message.id);
    return decryptPayload(this.spaceKey, fetched.payload.encryptedPayload);
  }
}
