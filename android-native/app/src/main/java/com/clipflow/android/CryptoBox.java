package com.clipflow.android;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoBox {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoBox() {
    }

    static byte[] deriveSpaceKey(String secret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject encrypt(String secret, String payload) throws Exception {
        byte[] key = deriveSpaceKey(secret);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));

        byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = Arrays.copyOfRange(encrypted, 0, encrypted.length - GCM_TAG_BYTES);
        byte[] tag = Arrays.copyOfRange(encrypted, encrypted.length - GCM_TAG_BYTES, encrypted.length);

        JSONObject body = new JSONObject();
        body.put("algorithm", "aes-256-gcm");
        body.put("nonce", base64Url(nonce));
        body.put("tag", base64Url(tag));
        body.put("ciphertext", base64Url(ciphertext));
        return body;
    }

    static String decrypt(String secret, JSONObject encryptedPayload) throws Exception {
        byte[] key = deriveSpaceKey(secret);
        byte[] nonce = fromBase64Url(encryptedPayload.getString("nonce"));
        byte[] ciphertext = fromBase64Url(encryptedPayload.getString("ciphertext"));
        byte[] tag = fromBase64Url(encryptedPayload.getString("tag"));
        byte[] combined = new byte[ciphertext.length + tag.length];

        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));

        return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
    }

    private static String base64Url(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] fromBase64Url(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
