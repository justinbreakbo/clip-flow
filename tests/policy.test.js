import assert from "node:assert/strict";
import test from "node:test";
import { createClip } from "../src/protocol.js";
import { classifyText, decideDelivery, detectSensitivity } from "../src/policy.js";

test("classifies URLs separately from plain text", () => {
  assert.equal(classifyText("https://github.com/openai"), "url");
  assert.equal(classifyText("hello world"), "text");
});

test("auto-syncs normal small text", () => {
  const clip = createClip({
    spaceId: "space",
    sourceDeviceId: "device",
    contentType: "text",
    payload: "hello"
  });

  assert.equal(decideDelivery(clip), "auto");
});

test("requires offer for sensitive-looking text", () => {
  const sensitivity = detectSensitivity({
    contentType: "text",
    payload: "api_key=abcdefghijklmnopqrstuvwxyz123456"
  });

  const clip = createClip({
    spaceId: "space",
    sourceDeviceId: "device",
    contentType: "text",
    payload: "api_key=abcdefghijklmnopqrstuvwxyz123456",
    sensitivity
  });

  assert.equal(sensitivity, "sensitive");
  assert.equal(decideDelivery(clip), "offer");
});

test("requires manual delivery for files", () => {
  const clip = createClip({
    spaceId: "space",
    sourceDeviceId: "device",
    contentType: "file",
    payload: "file metadata"
  });

  assert.equal(decideDelivery(clip), "manual");
});
