import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { readJson, sendJson, sendNotFound } from "./json-http.js";

const port = Number.parseInt(process.env.PORT ?? "42820", 10);
const activeDeviceTtlMs = Number.parseInt(process.env.ACTIVE_DEVICE_TTL_MS ?? "60000", 10);

const devices = new Map();
const queues = new Map();
const waiters = new Map();
const withheldPayloads = new Map();

function queueFor(deviceId) {
  const queue = queues.get(deviceId) ?? [];
  queues.set(deviceId, queue);
  return queue;
}

function registerDevice(body) {
  const device = {
    id: body.deviceId,
    name: body.name,
    platform: body.platform ?? "desktop",
    lastSeenAt: new Date().toISOString()
  };

  devices.set(device.id, device);
  queueFor(device.id);
  return device;
}

function touchDevice(deviceId) {
  const device = devices.get(deviceId);
  if (device) {
    device.lastSeenAt = new Date().toISOString();
  }
}

function isActiveDevice(device, now = Date.now()) {
  return now - new Date(device.lastSeenAt).getTime() <= activeDeviceTtlMs;
}

function activeDevices() {
  const now = Date.now();
  return [...devices.values()].filter((device) => isActiveDevice(device, now));
}

function routeClip(body) {
  const messageId = randomUUID();
  const message = {
    id: messageId,
    type: "clip.message",
    sourceDeviceId: body.sourceDeviceId,
    targetDeviceId: body.targetDeviceId ?? null,
    offer: body.offer,
    payload: body.payload ?? null,
    hasWithheldPayload: Boolean(body.withheldPayload),
    createdAt: new Date().toISOString()
  };

  if (body.withheldPayload) {
    withheldPayloads.set(messageId, {
      sourceDeviceId: body.sourceDeviceId,
      targetDeviceId: body.targetDeviceId ?? null,
      payload: body.withheldPayload,
      createdAt: message.createdAt
    });
  }

  touchDevice(body.sourceDeviceId);

  const targets = message.targetDeviceId
    ? [message.targetDeviceId]
    : activeDevices()
        .map((device) => device.id)
        .filter((deviceId) => deviceId !== message.sourceDeviceId);

  for (const target of targets) {
    queueFor(target).push(message);
    flushWaiters(target);
  }

  return { queuedFor: targets.length, messageId: message.id };
}

function fetchWithheldPayload(body) {
  const entry = withheldPayloads.get(body.messageId);
  if (!entry) {
    return { status: 404, body: { error: "payload_not_found" } };
  }

  if (entry.targetDeviceId && entry.targetDeviceId !== body.deviceId) {
    return { status: 403, body: { error: "payload_not_for_device" } };
  }

  withheldPayloads.delete(body.messageId);
  return { status: 200, body: { payload: entry.payload } };
}

function poll(deviceId) {
  const device = devices.get(deviceId);
  touchDevice(deviceId);

  const queue = queueFor(deviceId);
  const messages = queue.splice(0, queue.length);

  return {
    messages,
    devices: activeDevices()
  };
}

function waitersFor(deviceId) {
  const list = waiters.get(deviceId) ?? [];
  waiters.set(deviceId, list);
  return list;
}

function flushWaiters(deviceId) {
  const list = waiters.get(deviceId) ?? [];
  if (list.length === 0) {
    return;
  }

  waiters.set(deviceId, []);

  for (const waiter of list) {
    clearTimeout(waiter.timeout);
    sendJson(waiter.response, 200, poll(deviceId));
  }
}

function longPoll(deviceId, timeoutMs, response) {
  const queue = queueFor(deviceId);
  if (queue.length > 0 || timeoutMs <= 0) {
    sendJson(response, 200, poll(deviceId));
    return;
  }

  const timeout = setTimeout(() => {
    const list = waitersFor(deviceId);
    const index = list.findIndex((waiter) => waiter.response === response);
    if (index !== -1) {
      list.splice(index, 1);
    }

    sendJson(response, 200, poll(deviceId));
  }, Math.min(timeoutMs, 30000));

  waitersFor(deviceId).push({ response, timeout });
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", `http://localhost:${port}`);

    if (request.method === "GET" && url.pathname === "/health") {
      sendJson(response, 200, {
        ok: true,
        devices: devices.size,
        activeDevices: activeDevices().length
      });
      return;
    }

    if (request.method === "GET" && url.pathname === "/devices") {
      sendJson(response, 200, {
        devices: [...devices.values()].map((device) => ({
          ...device,
          active: isActiveDevice(device)
        }))
      });
      return;
    }

    if (request.method === "POST" && url.pathname === "/register") {
      const body = await readJson(request);
      if (!body.deviceId || !body.name) {
        sendJson(response, 400, { error: "deviceId_and_name_required" });
        return;
      }

      sendJson(response, 200, { device: registerDevice(body) });
      return;
    }

    if (request.method === "POST" && url.pathname === "/clips") {
      const body = await readJson(request);
      if (!body.sourceDeviceId || !body.offer) {
        sendJson(response, 400, { error: "sourceDeviceId_and_offer_required" });
        return;
      }

      sendJson(response, 200, routeClip(body));
      return;
    }

    if (request.method === "POST" && url.pathname === "/payloads/fetch") {
      const body = await readJson(request);
      if (!body.deviceId || !body.messageId) {
        sendJson(response, 400, { error: "deviceId_and_messageId_required" });
        return;
      }

      const result = fetchWithheldPayload(body);
      sendJson(response, result.status, result.body);
      return;
    }

    if (request.method === "GET" && url.pathname === "/poll") {
      const deviceId = url.searchParams.get("deviceId");
      if (!deviceId) {
        sendJson(response, 400, { error: "deviceId_required" });
        return;
      }

      const timeoutMs = Number.parseInt(url.searchParams.get("timeoutMs") ?? "0", 10);
      longPoll(deviceId, Number.isFinite(timeoutMs) ? timeoutMs : 0, response);
      return;
    }

    sendNotFound(response);
  } catch (error) {
    sendJson(response, 500, { error: "internal_error", message: error.message });
  }
});

server.listen(port, () => {
  console.log(`Clip Flow relay listening on http://localhost:${port}`);
});
