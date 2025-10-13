package com.workers.profesores.chat.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtDelegationService {

    @Value("${jwt.delegation.secret:}")
    private String delegationSecret;

    @Value("${jwt.delegation.expirationMinutes:5}")
    private int expirationMinutes;
    @Value("${backend.debug:false}")
    private boolean debug;
    @Value("${dump.secrets:false}")
    private boolean dumpSecrets;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JwtDelegationService.class);

    /**
     * Create a delegated Authorization header value (Bearer <token>) signed with HS256 using
     * the configured delegation secret. If delegationSecret is not configured, returns null.
     */
    public String createDelegatedAuthorizationHeader(UserClaims claims) {
        if (delegationSecret == null || delegationSecret.isBlank()) {
            if (debug) {
                logger.info("[JwtDelegationService] delegationSecret not configured - delegation disabled, will forward original token");
            }
            return null;
        }
        if (debug) {
            try {
                String prefix = delegationSecret.length() > 4 ? delegationSecret.substring(0,4) + "..." : delegationSecret;
                logger.debug("[JwtDelegationService] delegationSecret present (masked prefix): {} , expirationMinutes={}", prefix, expirationMinutes);
            } catch (Exception ignore) { }
        }
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder cb = new JWTClaimsSet.Builder();
            // issuer identified as chat-backend
            cb.issuer("chat-backend");
            // subject: encode as JSON string similar to access tokens {"usuario_id":..., "token_version":...}
            String subj = "anonymous";
            Integer tv = null;
            if (claims != null) tv = claims.tokenVersion;
            if (claims != null && claims.usuarioId != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String,Object> id = new java.util.HashMap<>();
                    id.put("usuario_id", claims.usuarioId);
                    if (tv != null) id.put("token_version", tv);
                    subj = om.writeValueAsString(id);
                } catch (Exception ignore) {
                    subj = String.valueOf(claims.usuarioId);
                }
            }
            cb.subject(subj);
            // standard timestamps
            cb.issueTime(Date.from(now));
            cb.expirationTime(Date.from(now.plusSeconds(60L * Math.max(1, expirationMinutes))));
            // custom claims
            if (claims != null) {
                if (claims.roles != null) cb.claim("roles", claims.roles);
                if (claims.academiaId != null) cb.claim("academia_id", claims.academiaId);
                if (claims.profesorUsuarioId != null) cb.claim("profesor_usuario_id", claims.profesorUsuarioId);
                if (tv != null) cb.claim("token_version", tv);
            }
            cb.claim("actor", "chat-backend");
            cb.claim("jti", UUID.randomUUID().toString());

            JWTClaimsSet cs = cb.build();
            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), cs);
            MACSigner signer = new MACSigner(delegationSecret.getBytes());
            signedJWT.sign(signer);
            String token = signedJWT.serialize();
            String headerVal = "Bearer " + token;
            if (debug) {
                try {
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(headerVal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                    String preview = headerVal.length() > 12 ? headerVal.substring(0, 8) + "..." : headerVal;
                    logger.debug("[JwtDelegationService] Created delegated token preview={} token_sha4={} subject={} roles={}", preview, sb.toString(), (claims != null && claims.usuarioId != null ? claims.usuarioId : "null"), (claims != null && claims.roles != null ? claims.roles : "[]"));
                } catch (Exception logEx) {
                    logger.warn("[JwtDelegationService] Failed to compute token fingerprint: {}", logEx.getMessage());
                }
            }
            // Optional: dump full delegated token and related diagnostics when explicitly enabled via properties
            if (dumpSecrets || debug) {
                try {
                    System.out.println("[JwtDelegationService][DUMP] Delegated Authorization header: " + headerVal);
                    System.out.println("[JwtDelegationService][DUMP] Claims: " + cs.toJSONObject().toString());

                    // Print token parts (header.payload.signature) and base64url-decode header and payload for easy inspection
                    try {
                        String tokenOnly = token;
                        String[] parts = tokenOnly.split("\\.");
                        if (parts.length == 3) {
                            String headerB64 = parts[0];
                            String payloadB64 = parts[1];
                            String sigB64 = parts[2];
                            System.out.println("[JwtDelegationService][DUMP] token_header_b64=" + headerB64);
                            System.out.println("[JwtDelegationService][DUMP] token_payload_b64=" + payloadB64);
                            System.out.println("[JwtDelegationService][DUMP] token_signature_b64=" + sigB64);
                            try {
                                java.util.Base64.Decoder urlDecoder = java.util.Base64.getUrlDecoder();
                                String headerJson = new String(urlDecoder.decode(headerB64), java.nio.charset.StandardCharsets.UTF_8);
                                String payloadJson = new String(urlDecoder.decode(payloadB64), java.nio.charset.StandardCharsets.UTF_8);
                                System.out.println("[JwtDelegationService][DUMP] token_header_json=" + headerJson);
                                System.out.println("[JwtDelegationService][DUMP] token_payload_json=" + payloadJson);
                            } catch (Exception decodeEx) {
                                System.out.println("[JwtDelegationService][DUMP] Failed to base64url-decode token parts: " + decodeEx.getMessage());
                            }
                        } else {
                            System.out.println("[JwtDelegationService][DUMP] Unexpected token parts count=" + (parts == null ? 0 : parts.length));
                        }
                        // Token full SHA-256 hex
                        try {
                            java.security.MessageDigest mdTok = java.security.MessageDigest.getInstance("SHA-256");
                            byte[] tokDigest = mdTok.digest(tokenOnly.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            StringBuilder tokHex = new StringBuilder();
                            for (byte b : tokDigest) tokHex.append(String.format("%02x", b));
                            System.out.println("[JwtDelegationService][DUMP] token_sha256=" + tokHex.toString());
                        } catch (Exception exTokSha) {
                            System.out.println("[JwtDelegationService][DUMP] Failed to compute token sha256: " + exTokSha.getMessage());
                        }
                    } catch (Exception exParts) {
                        System.out.println("[JwtDelegationService][DUMP] Error printing token parts: " + exParts.getMessage());
                    }

                    java.security.MessageDigest md2 = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] digest2 = md2.digest(delegationSecret == null ? new byte[0] : delegationSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder hs = new StringBuilder();
                    for (byte b : digest2) hs.append(String.format("%02x", b));
                    System.out.println("[JwtDelegationService][DUMP] delegationSecret_sha256=" + hs.toString() + " delegationSecret_len=" + (delegationSecret == null ? 0 : delegationSecret.length()));
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
            }
            return "Bearer " + token;
        } catch (Exception e) {
            // If debug, print stacktrace to stdout so it's captured by logs
            try {
                if (debug) {
                    System.out.println("[JwtDelegationService][ERROR] Failed to create delegated token: " + e.getMessage());
                    e.printStackTrace(System.out);
                }
            } catch (Exception ignore) { }
            throw new RuntimeException("Failed to create delegated token: " + e.getMessage(), e);
        }
    }

    @PostConstruct
    public void postConstruct() {
            if (debug) {
                String presence = (delegationSecret == null) ? "null" : (delegationSecret.isBlank() ? "blank" : "present");
                String prefix = "";
                if (delegationSecret != null && !delegationSecret.isBlank()) prefix = delegationSecret.length() > 4 ? delegationSecret.substring(0,4) + "..." : delegationSecret;
                logger.debug("[JwtDelegationService] PostConstruct: delegationSecret={} maskedPrefix={} expirationMinutes={}", presence, prefix, expirationMinutes);
                try {
                    if (delegationSecret != null && !delegationSecret.isBlank()) {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(delegationSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder hs = new StringBuilder();
                        for (byte b : digest) hs.append(String.format("%02x", b));
                        logger.debug(", delegationSecret_sha256={} delegationSecret_len={}", hs.toString(), delegationSecret.length());
                    }
                } catch (Exception ex) {
                    logger.warn("[JwtDelegationService] delegationSecret sha256 compute error: {}", ex.getMessage());
                }
            }
    }
}
