import assert from "node:assert/strict";
import test from "node:test";
import { spawn } from "node:child_process";

const port = 42991;
const baseUrl = `http://localhost:${port}`;

function startRelay() {
  const child = spawn(process.execPath, ["src/relay-server.js"], {
    env: { ...process.env, PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"]
  });

  return child;
}

async function waitForHealth() {
  const started = Date.now();

  while (Date.now() - started < 5000) {
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) {
        return;
      }
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }

  throw new Error("relay did not start");
}

async function post(path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });

  assert.equal(response.ok, true);
  return response.json();
}

test("relay registers devices, routes a clip, and returns it on poll", async () => {
  const relay = startRelay();

  try {
    await waitForHealth();
    await post("/register", { deviceId: "a", name: "MacBook" });
    await post("/register", { deviceId: "b", name: "Windows PC" });

    const routed = await post("/clips", {
      sourceDeviceId: "a",
      targetDeviceId: "b",
      offer: { clipId: "clip-1", contentType: "text" },
      payload: { encryptedPayload: { ciphertext: "opaque" } }
    });

    assert.equal(routed.queuedFor, 1);

    const pollResponse = await fetch(`${baseUrl}/poll?deviceId=b`);
    const body = await pollResponse.json();

    assert.equal(body.messages.length, 1);
    assert.equal(body.messages[0].sourceDeviceId, "a");
    assert.equal(body.messages[0].payload.encryptedPayload.ciphertext, "opaque");
  } finally {
    relay.kill();
  }
});

test("long poll waits and resolves when a message arrives", async () => {
  const relay = startRelay();

  try {
    await waitForHealth();
    await post("/register", { deviceId: "sender", name: "Sender" });
    await post("/register", { deviceId: "receiver", name: "Receiver" });

    const pendingPoll = fetch(`${baseUrl}/poll?deviceId=receiver&timeoutMs=5000`).then((response) =>
      response.json()
    );

    await new Promise((resolve) => setTimeout(resolve, 150));

    await post("/clips", {
      sourceDeviceId: "sender",
      targetDeviceId: "receiver",
      offer: { clipId: "clip-long-poll", contentType: "text" },
      payload: { encryptedPayload: { ciphertext: "opaque-long-poll" } }
    });

    const body = await pendingPoll;

    assert.equal(body.messages.length, 1);
    assert.equal(body.messages[0].offer.clipId, "clip-long-poll");
    assert.equal(body.messages[0].payload.encryptedPayload.ciphertext, "opaque-long-poll");
  } finally {
    relay.kill();
  }
});

test("withheld payload is only returned after explicit fetch", async () => {
  const relay = startRelay();

  try {
    await waitForHealth();
    await post("/register", { deviceId: "sender", name: "Sender" });
    await post("/register", { deviceId: "receiver", name: "Receiver" });

    const routed = await post("/clips", {
      sourceDeviceId: "sender",
      targetDeviceId: "receiver",
      offer: { clipId: "clip-sensitive", contentType: "text", sensitivity: "sensitive" },
      withheldPayload: { encryptedPayload: { ciphertext: "withheld-ciphertext" } }
    });

    const pollResponse = await fetch(`${baseUrl}/poll?deviceId=receiver`);
    const body = await pollResponse.json();

    assert.equal(body.messages.length, 1);
    assert.equal(body.messages[0].payload, null);
    assert.equal(body.messages[0].hasWithheldPayload, true);

    const fetched = await post("/payloads/fetch", {
      deviceId: "receiver",
      messageId: routed.messageId
    });

    assert.equal(fetched.payload.encryptedPayload.ciphertext, "withheld-ciphertext");
  } finally {
    relay.kill();
  }
});
