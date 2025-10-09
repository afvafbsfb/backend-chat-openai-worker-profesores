package com.workers.profesores.chat.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev Jwt configuration: provides a very permissive JwtDecoder when none is defined.
 * This is intended for local development only. It decodes the JWT payload (second segment)
 * as base64 JSON and returns a Jwt with those claims without signature verification.
 */
@Configuration
public class DevJwtConfig {

    private final ObjectMapper om = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder permissiveJwtDecoder() {
        return token -> {
            try {
                String[] parts = token.split("\\.");
                if (parts.length < 2) throw new JwtException("Invalid token format");
                String payload = parts[1];
                // Pad base64 if necessary
                int mod = payload.length() % 4;
                if (mod != 0) payload += "=".repeat(4 - mod);
                String json = new String(Base64.getUrlDecoder().decode(payload));
                @SuppressWarnings("unchecked")
                Map<String,Object> claims = om.readValue(json, Map.class);

                // Ensure timestamp claims are Instant instances (Jwt.Builder requires Instant)
                Map<String,Object> safeClaims = new HashMap<>(claims);
                String[] tsKeys = new String[]{"iat", "exp", "nbf"};
                for (String k : tsKeys) {
                    Object v = claims.get(k);
                    if (v instanceof Number) {
                        long n = ((Number) v).longValue();
                        // heuristics: values > 1e12 are probably milliseconds
                        Instant inst = (n > 1_000_000_000_000L) ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
                        safeClaims.put(k, inst);
                    } else if (v instanceof String) {
                        try {
                            long n = Long.parseLong((String) v);
                            Instant inst = (n > 1_000_000_000_000L) ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
                            safeClaims.put(k, inst);
                        } catch (NumberFormatException ignored) {
                            // leave as-is
                        }
                    }
                }

                return Jwt.withTokenValue(token)
                        .headers(h -> h.put("alg", "none"))
                        .claims(c -> c.putAll(safeClaims))
                        .build();
            } catch (JwtException je) {
                throw je;
            } catch (Exception e) {
                throw new JwtException("Failed to decode token: " + e.getMessage(), e);
            }
        };
    }
}
