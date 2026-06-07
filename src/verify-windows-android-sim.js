import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { ClipClient } from "./clip-client.js";
import { getClipboardText, setClipboardText } from "./clipboard-windows.js";

const port = 42930;
const relayUrl = `http://localhost:${port}`;
const secret = "windows-android-sim-secret";
const verifyDir = join(process.cwd(), ".clip-flow-android-verify");
const windowsName = "Windows Verification Agent";

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

function stop(child) {
  if (!child.killed) {
    child.kill();
  }
}

async function waitFor(predicate, label, timeoutMs = 12000) {
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

async function main() {
  const originalClipboard = await getClipboardText().catch(() => "");
  const relay = spawnNode(["src/relay-server.js"], { PORT: String(port) });
  const windows = spawnNode(["src/agent.js"], {
    CLIP_FLOW_SECRET: secret,
    CLIP_FLOW_RELAY: relayUrl,
    CLIP_FLOW_DEVICE_NAME: windowsName,
    CLIP_FLOW_DATA_DIR: verifyDir,
    CLIP_FLOW_CLIPBOARD_POLL_MS: "300",
    CLIP_FLOW_RELAY_POLL_TIMEOUT_MS: "5000",
    CLIP_FLOW_AUTO_ACCEPT_OFFERS: "true"
  });
  const android = new ClipClient({
    relayUrl,
    secret,
    deviceName: "Android Simulator",
    platform: "android",
    dataDir: verifyDir
  });

  try {
    await waitFor(async () => {
      try {
        const response = await fetch(`${relayUrl}/health`);
        return response.ok;
      } catch {
        return false;
      }
    }, "relay health");

    await android.register();

    const windowsDevice = await waitFor(async () => {
      const data = await android.poll();
      return data.devices.find((device) => device.name === windowsName);
    }, "Windows agent registration");

    const windowsToAndroid = `windows to android ${randomUUID()}`;
    await setClipboardText(windowsToAndroid);

    const outbound = await waitFor(async () => {
      const data = await android.poll();
      for (const message of data.messages) {
        const value = android.decryptMessage(message);
        if (value === windowsToAndroid) {
          return value;
        }
      }
      return null;
    }, "Windows clipboard -> Android simulator");

    const androidToWindows = `android to windows ${randomUUID()}`;
    await android.sendText(androidToWindows, {
      targetDeviceId: windowsDevice.id
    });

    await waitFor(async () => {
      const value = await getClipboardText();
      return value === androidToWindows ? value : null;
    }, "Android simulator -> Windows clipboard");

    const androidSensitive = `api_key=androidsim${randomUUID().replaceAll("-", "")}`;
    await android.sendText(androidSensitive, {
      targetDeviceId: windowsDevice.id
    });

    await waitFor(async () => {
      const value = await getClipboardText();
      return value === androidSensitive ? value : null;
    }, "Android simulator offer -> Windows auto-accepted clipboard");

    console.log("Windows + Android simulator verification passed");
    console.log(`windows device id: ${windowsDevice.id}`);
    console.log(`android device id: ${android.deviceId}`);
    console.log(`windows -> android: ${outbound}`);
    console.log(`android -> windows: ${androidToWindows}`);
    console.log(`android offer -> windows: ${androidSensitive}`);
  } finally {
    await setClipboardText(originalClipboard).catch(() => {});
    stop(windows);
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
