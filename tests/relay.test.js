import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import { createSpaceKey } from "../src/crypto-box.js";
import { SimulatedDevice } from "../src/device.js";
import { InMemoryRelay } from "../src/relay.js";

test("delivers an encrypted clip to an online device", () => {
  const relay = new InMemoryRelay();
  const spaceId = randomUUID();
  const spaceKey = createSpaceKey();

  const mac = new SimulatedDevice({ name: "MacBook", platform: "macos", spaceId, spaceKey, relay });
  const pc = new SimulatedDevice({ name: "Windows PC", platform: "windows", spaceId, spaceKey, relay });

  mac.connect();
  pc.connect();

  const result = mac.copyText("hello from mac", pc.id);

  assert.deepEqual(result, { delivered: true, queued: false });
  assert.equal(pc.clipboard, "hello from mac");
  assert.equal(pc.offers.length, 1);
});

test("queues an encrypted clip and flushes when target connects", () => {
  const relay = new InMemoryRelay();
  const spaceId = randomUUID();
  const spaceKey = createSpaceKey();

  const mac = new SimulatedDevice({ name: "MacBook", platform: "macos", spaceId, spaceKey, relay });
  const android = new SimulatedDevice({ name: "Android", platform: "android", spaceId, spaceKey, relay });

  mac.connect();

  const result = mac.copyText("queued text", android.id);

  assert.deepEqual(result, { delivered: false, queued: true });
  assert.equal(android.clipboard, "");

  android.connect();

  assert.equal(android.clipboard, "queued text");
  assert.equal(android.offers.length, 1);
});

test("sends only an offer for sensitive-looking content", () => {
  const relay = new InMemoryRelay();
  const spaceId = randomUUID();
  const spaceKey = createSpaceKey();

  const mac = new SimulatedDevice({ name: "MacBook", platform: "macos", spaceId, spaceKey, relay });
  const pc = new SimulatedDevice({ name: "Windows PC", platform: "windows", spaceId, spaceKey, relay });

  mac.connect();
  pc.connect();

  const result = mac.copyText("api_key=abcdefghijklmnopqrstuvwxyz123456", pc.id);

  assert.deepEqual(result, { delivered: true, queued: false });
  assert.equal(pc.clipboard, "");
  assert.equal(pc.offers.length, 1);
  assert.equal(pc.offers[0].deliveryMode, "offer");
  assert.equal(pc.offers[0].sensitivity, "sensitive");
});
