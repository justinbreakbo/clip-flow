export async function readJson(request) {
  const chunks = [];

  for await (const chunk of request) {
    chunks.push(chunk);
  }

  if (chunks.length === 0) {
    return {};
  }

  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

export function sendJson(response, statusCode, body) {
  const payload = JSON.stringify(body);
  const length = Buffer.byteLength(payload);

  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": length,
    "Connection": "close"
  });
  response.end(payload);
}

export function sendNotFound(response) {
  sendJson(response, 404, { error: "not_found" });
}
