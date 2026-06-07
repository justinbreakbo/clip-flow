import { join } from "node:path";
import { ClipClient } from "./clip-client.js";
import { getAndroidClipboardText, setAndroidClipboardText } from "./clipboard-android-termux.js";

const relayUrl = process.env.CLIP_FLOW_RELAY ?? "http://localhost:42820";
const secret = process.env.CLIP_FLOW_SECRET ?? "local-dev-secret";
const deviceName = process.env.CLIP_FLOW_DEVICE_NAME ?? "Android";
const dataDir = process.env.CLIP_FLOW_DATA_DIR ?? join(process.cwd(), ".clip-flow");
const pollTimeoutMs = Number.parseInt(process.env.CLIP_FLOW_RELAY_POLL_TIMEOUT_MS ?? "25000", 10);
const autoAcceptOffers = process.env.CLIP_FLOW_AUTO_ACCEPT_OFFERS === "true";
const useTermuxClipboard = process.env.CLIP_FLOW_TERMUX_CLIPBOARD === "true";

const client = new ClipClient({
  relayUrl,
  secret,
  deviceName,
  platform: "android",
  dataDir
});

function usage() {
  console.log(`Clip Flow Android simulator

Usage:
  node src/android-sim.js send "text to send"
  node src/android-sim.js send-clipboard
  node src/android-sim.js poll
  node src/android-sim.js watch

Environment:
  CLIP_FLOW_RELAY=http://<windows-ip>:42820
  CLIP_FLOW_SECRET=change-this-local-secret
  CLIP_FLOW_DEVICE_NAME=Android
  CLIP_FLOW_AUTO_ACCEPT_OFFERS=true
  CLIP_FLOW_TERMUX_CLIPBOARD=true
`);
}

async function handleMessages(messages) {
  for (const message of messages) {
    const autoPayload = client.decryptMessage(message);
    if (autoPayload !== null) {
      console.log(`[receive] ${message.offer.contentType}: ${autoPayload}`);
      if (useTermuxClipboard) {
        await setAndroidClipboardText(autoPayload);
        console.log("[clipboard] wrote to Termux clipboard");
      }
      continue;
    }

    console.log(`[offer] ${message.offer.contentType} from ${message.sourceDeviceId}`);
    if (autoAcceptOffers) {
      const value = await client.acceptOffer(message);
      console.log(`[accept] ${value}`);
      if (useTermuxClipboard) {
        await setAndroidClipboardText(value);
        console.log("[clipboard] wrote accepted offer to Termux clipboard");
      }
    }
  }
}

async function main() {
  const command = process.argv[2];
  await client.register();

  if (!command || command === "help") {
    usage();
    return;
  }

  if (command === "send") {
    const value = process.argv.slice(3).join(" ");
    if (!value) {
      throw new Error("send requires text");
    }

    const { clip, result } = await client.sendText(value);
    console.log(`[send] ${clip.contentType} ${clip.deliveryMode}, queuedFor=${result.queuedFor}`);
    return;
  }

  if (command === "send-clipboard") {
    const value = await getAndroidClipboardText();
    const { clip, result } = await client.sendText(value);
    console.log(`[send] clipboard ${clip.contentType} ${clip.deliveryMode}, queuedFor=${result.queuedFor}`);
    return;
  }

  if (command === "poll") {
    const data = await client.poll();
    await handleMessages(data.messages);
    return;
  }

  if (command === "watch") {
    console.log(`Android simulator`);
    console.log(`device: ${deviceName} (${client.deviceId})`);
    console.log(`relay:  ${relayUrl}`);
    console.log(`mode:   long-poll watch ${pollTimeoutMs}ms`);
    console.log("");

    for (;;) {
      const data = await client.poll({ timeoutMs: pollTimeoutMs });
      await handleMessages(data.messages);
    }
  }

  usage();
}

main().catch((error) => {
  console.error(`[error] ${error.message}`);
  process.exitCode = 1;
});
