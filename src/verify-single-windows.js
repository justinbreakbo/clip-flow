import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { decryptPayload, encryptPayload } from "./crypto-box.js";
import { setClipboardText, getClipboardText } from "./clipboard-windows.js";
import { createClip, createOffer, createPayloadMessage } from "./protocol.js";
import { deriveSpaceKey } from "./key.js";

const port = 42920;
const relayUrl = `http://localhost:${port}`;
const secret = "single-windows-verify-secret";
const spaceKey = deriveSpaceKey(secret);
const verifyDir = join(process.cwd(), ".clip-flow-verify");
const agentName = "Verification Agent";
const virtualDeviceId = "virtual-device";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function spawnNode(args, env = {}) {
  return spawn(process.execPath, args, {
    cwd: process.cwd(),
    env: { ...process.env, ...env },
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true
  });
}

async function waitFor(predicate, label, timeoutMs = 10000) {
  const started = Date.now();

  while (Date.now() - started < timeoutMs) {
    const value = await predicate();
    if (value) {
      return value;
    }
    await sleep(150);
  }

  throw new Error(`Timed out waiting for ${label}`);
}

async function post(path, body) {
  const response = await fetch(`${relayUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status}`);
  }

  return response.json();
}

async function get(path) {
  const response = await fetch(`${relayUrl}${path}`);

  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status}`);
  }

  return response.json();
}

function stop(child) {
  if (!child.killed) {
    child.kill();
  }
}

async function main() {
  const originalClipboard = await getClipboardText().catch(() => "");
  const relay = spawnNode(["src/relay-server.js"], { PORT: String(port) });
  const agent = spawnNode(["src/agent.js"], {
    CLIP_FLOW_SECRET: secret,
    CLIP_FLOW_RELAY: relayUrl,
    CLIP_FLOW_DEVICE_NAME: agentName,
    CLIP_FLOW_DATA_DIR: verifyDir,
    CLIP_FLOW_CLIPBOARD_POLL_MS: "300",
    CLIP_FLOW_RELAY_POLL_TIMEOUT_MS: "5000",
    CLIP_FLOW_AUTO_ACCEPT_OFFERS: "true"
  });

  try {
    await waitFor(async () => {
      try {
        const health = await get("/health");
        return health.ok;
      } catch {
        return false;
      }
    }, "relay health");

    await post("/register", {
      deviceId: virtualDeviceId,
      name: "Virtual Remote",
      platform: "test"
    });

    const agentDevice = await waitFor(async () => {
      const data = await get(`/poll?deviceId=${encodeURIComponent(virtualDeviceId)}`);
      return data.devices.find((device) => device.name === agentName);
    }, "agent registration");

    const outboundText = `clip-flow outbound ${randomUUID()}`;
    await setClipboardText(outboundText);

    const outbound = await waitFor(async () => {
      const data = await get(`/poll?deviceId=${encodeURIComponent(virtualDeviceId)}`);
      for (const message of data.messages) {
        if (!message.payload?.encryptedPayload) {
          continue;
        }

        const text = decryptPayload(spaceKey, message.payload.encryptedPayload);
        if (text === outboundText) {
          return { message, text };
        }
      }
      return null;
    }, "real clipboard -> virtual device");

    const inboundText = `clip-flow inbound ${randomUUID()}`;
    const clip = createClip({
      spaceId: "local-space",
      sourceDeviceId: virtualDeviceId,
      contentType: "text",
      payload: inboundText,
      deliveryMode: "auto"
    });
    const encryptedPayload = encryptPayload(spaceKey, inboundText);

    await post("/clips", {
      sourceDeviceId: virtualDeviceId,
      targetDeviceId: agentDevice.id,
      offer: createOffer(clip, agentDevice.id),
      payload: createPayloadMessage(clip, encryptedPayload)
    });

    await waitFor(async () => {
      const value = await getClipboardText();
      return value === inboundText ? value : null;
    }, "virtual device -> real clipboard");

    const sensitiveText = `api_key=singlewindows${randomUUID().replaceAll("-", "")}`;
    const sensitiveClip = createClip({
      spaceId: "local-space",
      sourceDeviceId: virtualDeviceId,
      contentType: "text",
      payload: sensitiveText,
      sensitivity: "sensitive",
      deliveryMode: "offer"
    });
    const sensitivePayload = encryptPayload(spaceKey, sensitiveText);

    await post("/clips", {
      sourceDeviceId: virtualDeviceId,
      targetDeviceId: agentDevice.id,
      offer: createOffer(sensitiveClip, agentDevice.id),
      withheldPayload: createPayloadMessage(sensitiveClip, sensitivePayload)
    });

    await waitFor(async () => {
      const value = await getClipboardText();
      return value === sensitiveText ? value : null;
    }, "withheld offer fetch -> real clipboard");

    console.log("Single Windows verification passed");
    console.log(`agent device id: ${agentDevice.id}`);
    console.log(`outbound verified: ${outbound.text}`);
    console.log(`inbound verified:  ${inboundText}`);
    console.log(`offer fetch verified: ${sensitiveText}`);
  } finally {
    await setClipboardText(originalClipboard).catch(() => {});
    stop(agent);
    stop(relay);
    if (existsSync(verifyDir)) {
      rmSync(verifyDir, { recursive: true, force: true });
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
