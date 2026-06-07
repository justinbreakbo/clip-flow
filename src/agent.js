import { join } from "node:path";
import { ClipClient } from "./clip-client.js";
import { getClipboardText, setClipboardText } from "./clipboard-windows.js";

const relayUrl = process.env.CLIP_FLOW_RELAY ?? "http://localhost:42820";
const deviceName = process.env.CLIP_FLOW_DEVICE_NAME ?? "Windows PC";
const clipboardPollMs = Number.parseInt(process.env.CLIP_FLOW_CLIPBOARD_POLL_MS ?? "1000", 10);
const relayPollTimeoutMs = Number.parseInt(process.env.CLIP_FLOW_RELAY_POLL_TIMEOUT_MS ?? "25000", 10);
const autoAcceptOffers = process.env.CLIP_FLOW_AUTO_ACCEPT_OFFERS === "true";
const secret = process.env.CLIP_FLOW_SECRET ?? "local-dev-secret";
const dataDir = process.env.CLIP_FLOW_DATA_DIR ?? join(process.cwd(), ".clip-flow");

const client = new ClipClient({
  relayUrl,
  secret,
  deviceName,
  platform: "windows",
  dataDir
});

let lastLocalClipboard = "";
let lastAppliedRemote = "";

async function register() {
  await client.register();
}

async function sendClipboard(value) {
  const { clip } = await client.sendText(value, {
    targetDeviceId: process.env.CLIP_FLOW_TARGET_DEVICE_ID || null
  });

  if (clip.deliveryMode === "auto") {
    console.log(`[send] ${clip.contentType} auto synced (${value.length} chars)`);
  } else {
    console.log(`[offer] ${clip.contentType} requires confirmation; payload withheld`);
  }
}

async function watchLocalClipboard() {
  const value = await getClipboardText();
  if (!value || value === lastLocalClipboard || value === lastAppliedRemote) {
    return;
  }

  lastLocalClipboard = value;
  await sendClipboard(value);
}

async function applyRemoteValue(sourceDeviceId, value, action = "receive") {
  lastAppliedRemote = value;
  lastLocalClipboard = value;
  await setClipboardText(value);
  console.log(`[${action}] copied from ${sourceDeviceId}: ${value.slice(0, 80)}`);
}

async function pollRemote() {
  const data = await client.poll({ timeoutMs: relayPollTimeoutMs });

  for (const message of data.messages) {
    const autoPayload = client.decryptMessage(message);
    if (autoPayload !== null) {
      await applyRemoteValue(message.sourceDeviceId, autoPayload);
      continue;
    }

    console.log(`[offer] from ${message.sourceDeviceId}: ${message.offer.contentType}, confirmation needed`);
    if (autoAcceptOffers && message.hasWithheldPayload) {
      const value = await client.acceptOffer(message);
      await applyRemoteValue(message.sourceDeviceId, value, "accept");
    }
  }
}

async function clipboardLoop() {
  try {
    await register();
    await watchLocalClipboard();
  } catch (error) {
    console.error(`[warn] ${error.message}`);
  } finally {
    setTimeout(clipboardLoop, clipboardPollMs);
  }
}

async function relayLoop() {
  try {
    await register();
    await pollRemote();
  } catch (error) {
    console.error(`[warn] ${error.message}`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
  } finally {
    relayLoop();
  }
}

console.log("Clip Flow agent");
console.log(`device: ${deviceName} (${client.deviceId})`);
console.log(`relay:  ${relayUrl}`);
console.log(`mode:   Windows clipboard polling every ${clipboardPollMs}ms`);
console.log(`relay:  long-poll receive timeout ${relayPollTimeoutMs}ms`);
console.log(`offers: ${autoAcceptOffers ? "auto-accept enabled" : "confirmation required"}`);
console.log("");

clipboardLoop();
relayLoop();
