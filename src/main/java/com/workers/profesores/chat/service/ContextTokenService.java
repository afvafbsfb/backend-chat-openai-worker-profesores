package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class ContextTokenService {
    private final ObjectMapper om = new ObjectMapper();
    @Value("${context.token.secret:change-me}")
    private String secret;

    // Simple opaque token: base64(payload) + "." + base64(signature)
    // Not a full JWT here to keep dependencies minimal; can be swapped to JWS later.
    public String sign(Map<String, Object> payload) {
        try {
            String json = om.writeValueAsString(payload);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            String p64 = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
            String sig = hmacSha256(p64, secret);
            String s64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.getBytes(StandardCharsets.UTF_8));
            return p64 + "." + s64;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> verify(String token) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) return null;
            String p64 = parts[0];
            String s64 = parts[1];
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(p64, secret).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(s64)) return null;
            byte[] data = Base64.getUrlDecoder().decode(p64);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(new String(data, StandardCharsets.UTF_8), HashMap.class);
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    private String hmacSha256(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
