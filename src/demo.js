import { randomUUID } from "node:crypto";
import { createSpaceKey } from "./crypto-box.js";
import { SimulatedDevice } from "./device.js";
import { InMemoryRelay } from "./relay.js";

const relay = new InMemoryRelay();
const spaceId = randomUUID();
const spaceKey = createSpaceKey();

const mac = new SimulatedDevice({
  name: "MacBook",
  platform: "macos",
  spaceId,
  spaceKey,
  relay
});

const windows = new SimulatedDevice({
  name: "Windows PC",
  platform: "windows",
  spaceId,
  spaceKey,
  relay
});

mac.connect();
windows.connect();

const result = mac.copyText("https://github.com/openai", windows.id);

console.log("delivery:", result);
console.log("windows clipboard:", windows.clipboard);
console.log("windows recent:", windows.recent.list().map((clip) => ({
  type: clip.contentType,
  source: clip.sourceDeviceId,
  payload: clip.payload
})));
