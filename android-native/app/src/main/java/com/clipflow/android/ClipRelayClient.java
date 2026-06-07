package com.clipflow.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

final class ClipRelayClient {
    interface MessageHandler {
        void onPlainText(String sourceDeviceId, String text);
        void onOffer(String sourceDeviceId, JSONObject message);
        void onLog(String text);
    }

    private static final Pattern URL_PATTERN = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "\\b(?:api[_-]?key|access[_-]?token|secret|password|passwd)\\b\\s*[:=]|\\b\\d{6}\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final String relayUrl;
    private final String deviceId;
    private final String deviceName;
    private final String secret;

    ClipRelayClient(String relayUrl, String deviceId, String deviceName, String secret) {
        this.relayUrl = trimTrailingSlash(relayUrl);
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.secret = secret;
    }

    String getDeviceId() {
        return deviceId;
    }

    void register() throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("name", deviceName);
        body.put("platform", "android-native");
        post("/register", body);
    }

    JSONObject sendText(String text) throws Exception {
        String contentType = URL_PATTERN.matcher(text.trim()).matches() ? "url" : "text";
        boolean sensitive = SENSITIVE_PATTERN.matcher(text).find();
        String deliveryMode = sensitive ? "offer" : "auto";
        String clipId = UUID.randomUUID().toString();
        String now = IsoClock.now();
        String expires = IsoClock.tomorrow();
        JSONObject encryptedPayload = CryptoBox.encrypt(secret, text);

        JSONObject offer = new JSONObject();
        offer.put("type", "clip.offer");
        offer.put("messageId", UUID.randomUUID().toString());
        offer.put("clipId", clipId);
        offer.put("spaceId", "local-space");
        offer.put("sourceDeviceId", deviceId);
        offer.put("targetDeviceId", JSONObject.NULL);
        offer.put("contentType", contentType);
        offer.put("size", text.getBytes(StandardCharsets.UTF_8).length);
        offer.put("sensitivity", sensitive ? "sensitive" : "normal");
        offer.put("deliveryMode", deliveryMode);
        offer.put("createdAt", now);
        offer.put("expiresAt", expires);
        offer.put("metadata", new JSONObject());

        JSONObject payload = new JSONObject();
        payload.put("type", "clip.payload");
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("clipId", clipId);
        payload.put("spaceId", "local-space");
        payload.put("sourceDeviceId", deviceId);
        payload.put("contentType", contentType);
        payload.put("encryptedPayload", encryptedPayload);
        payload.put("createdAt", now);
        payload.put("expiresAt", expires);

        JSONObject body = new JSONObject();
        body.put("sourceDeviceId", deviceId);
        body.put("offer", offer);
        body.put("payload", "auto".equals(deliveryMode) ? payload : JSONObject.NULL);
        body.put("withheldPayload", "auto".equals(deliveryMode) ? JSONObject.NULL : payload);

        return post("/clips", body);
    }

    void pollOnce(int timeoutMs, boolean autoAcceptOffers, MessageHandler handler) throws Exception {
        JSONObject response;
        try {
            response = get("/poll?deviceId=" + deviceId + "&timeoutMs=" + timeoutMs);
        } catch (Exception error) {
            if (!isTransientConnectionEnd(error)) {
                throw error;
            }

            handler.onLog("拉取连接结束，已按空结果处理");
            return;
        }

        JSONArray messages = response.getJSONArray("messages");

        for (int index = 0; index < messages.length(); index += 1) {
            JSONObject message = messages.getJSONObject(index);
            JSONObject payload = message.optJSONObject("payload");

            if (payload != null && payload.has("encryptedPayload")) {
                String value = CryptoBox.decrypt(secret, payload.getJSONObject("encryptedPayload"));
                handler.onPlainText(message.getString("sourceDeviceId"), value);
                continue;
            }

            handler.onOffer(message.getString("sourceDeviceId"), message);
            if (autoAcceptOffers && message.optBoolean("hasWithheldPayload", false)) {
                JSONObject fetched = fetchWithheldPayload(message.getString("id"));
                String value = CryptoBox.decrypt(secret, fetched.getJSONObject("payload").getJSONObject("encryptedPayload"));
                handler.onPlainText(message.getString("sourceDeviceId"), value);
            }
        }
    }

    JSONObject fetchWithheldPayload(String messageId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("messageId", messageId);
        return post("/payloads/fetch", body);
    }

    private JSONObject get(String path) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        try {
            return readJson(connection);
        } catch (Exception error) {
            if (!isTransientConnectionEnd(error)) {
                throw error;
            }

            connection.disconnect();
            return readJson(open(path, "GET"));
        }
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(path, "POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        return readJson(connection);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(relayUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(35000);
        connection.setRequestProperty("Connection", "close");
        return connection;
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + builder);
        }

        return new JSONObject(builder.toString());
    }

    private boolean isTransientConnectionEnd(Exception error) {
        if (error instanceof EOFException || error instanceof SocketException || error instanceof ProtocolException) {
            return true;
        }

        String message = error.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.US).contains("unexpected end of stream");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
