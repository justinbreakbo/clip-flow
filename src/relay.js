import { createOffer, createPayloadMessage, isExpired } from "./protocol.js";

export class InMemoryRelay {
  constructor({ now = () => new Date() } = {}) {
    this.now = now;
    this.devices = new Map();
    this.mailbox = new Map();
  }

  connect(device) {
    this.devices.set(device.id, device);
    this.flushMailbox(device.id);
  }

  disconnect(deviceId) {
    this.devices.delete(deviceId);
  }

  sendClip({ clip, encryptedPayload, targetDeviceId }) {
    const offer = createOffer(clip, targetDeviceId);
    const shouldDeliverPayload = clip.deliveryMode === "auto";
    const payload = shouldDeliverPayload ? createPayloadMessage(clip, encryptedPayload) : null;
    const packet = { offer, payload };

    const target = this.devices.get(targetDeviceId);
    if (target) {
      target.receiveOffer(offer);
      if (payload) {
        target.receivePayload(payload);
      }
      return { delivered: true, queued: false };
    }

    const queue = this.mailbox.get(targetDeviceId) ?? [];
    queue.push(packet);
    this.mailbox.set(targetDeviceId, queue);
    return { delivered: false, queued: true };
  }

  flushMailbox(deviceId) {
    const target = this.devices.get(deviceId);
    if (!target) {
      return;
    }

    const queue = this.mailbox.get(deviceId) ?? [];
    const remaining = [];

    for (const packet of queue) {
      if (isExpired(packet.offer, this.now())) {
        continue;
      }

      target.receiveOffer(packet.offer);
      if (packet.payload) {
        target.receivePayload(packet.payload);
      }
    }

    this.mailbox.set(deviceId, remaining);
  }
}
