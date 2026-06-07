import { randomUUID } from "node:crypto";
import { encryptPayload, decryptPayload } from "./crypto-box.js";
import { classifyText, decideDelivery, detectSensitivity } from "./policy.js";
import { createClip } from "./protocol.js";
import { RecentStore } from "./recent-store.js";

export class SimulatedDevice {
  constructor({ name, platform, spaceId, spaceKey, relay }) {
    this.id = randomUUID();
    this.name = name;
    this.platform = platform;
    this.spaceId = spaceId;
    this.spaceKey = spaceKey;
    this.relay = relay;
    this.recent = new RecentStore();
    this.offers = [];
    this.clipboard = "";
  }

  connect() {
    this.relay.connect(this);
  }

  disconnect() {
    this.relay.disconnect(this.id);
  }

  copyText(text, targetDeviceId, context = {}) {
    const contentType = classifyText(text);
    const sensitivity = detectSensitivity({
      contentType,
      payload: text,
      sourceApp: context.sourceApp
    });

    const initialClip = createClip({
      spaceId: this.spaceId,
      sourceDeviceId: this.id,
      contentType,
      payload: text,
      sensitivity
    });

    const deliveryMode = decideDelivery(initialClip, context.settings);
    const clip = { ...initialClip, deliveryMode };
    const encryptedPayload = encryptPayload(this.spaceKey, text);

    this.recent.add(clip);

    return this.relay.sendClip({
      clip,
      encryptedPayload,
      targetDeviceId
    });
  }

  receiveOffer(offer) {
    this.offers.push(offer);
  }

  receivePayload(payloadMessage) {
    const payload = decryptPayload(this.spaceKey, payloadMessage.encryptedPayload);
    this.clipboard = payload;
    this.recent.add({
      id: payloadMessage.clipId,
      spaceId: payloadMessage.spaceId,
      sourceDeviceId: payloadMessage.sourceDeviceId,
      createdAt: payloadMessage.createdAt,
      expiresAt: payloadMessage.expiresAt,
      contentType: payloadMessage.contentType,
      payload,
      size: Buffer.byteLength(payload, "utf8"),
      metadata: {},
      sensitivity: "normal",
      deliveryMode: "auto"
    });
  }
}
