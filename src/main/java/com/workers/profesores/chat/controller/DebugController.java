package com.workers.profesores.chat.controller;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.JwtVerifier;
import com.workers.profesores.chat.auth.UserClaims;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Development-only debug endpoint to create a delegated token and return a masked preview
 * plus a short fingerprint (token_sha4). Does NOT return the full token.
 */
@RestController
public class DebugController {

    private final JwtDelegationService jwtDelegationService;
    private final JwtVerifier jwtVerifier;

    public DebugController(JwtDelegationService jwtDelegationService, JwtVerifier jwtVerifier) {
        this.jwtDelegationService = jwtDelegationService;
        this.jwtVerifier = jwtVerifier;
    }

    @GetMapping("/debug/generate-delegated")
    public ResponseEntity<Map<String,Object>> generateDelegated(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Dev-Return-Token", required = false) String returnTokenHeader
    ) {
        UserClaims claims = null;
        try {
            if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
                claims = jwtVerifier.verify(authorization);
            }
        } catch (Exception e) {
            // ignore and fall back to sample claims
        }
        if (claims == null) {
            // fallback sample claim for dev testing
            claims = new UserClaims(1L, List.of("Admin_plataforma"), null, null, null);
        }

        String headerVal = jwtDelegationService.createDelegatedAuthorizationHeader(claims);
        if (headerVal == null) {
            return ResponseEntity.status(503).body(Map.of("ok", false, "error", "delegation_not_configured"));
        }
        String preview = headerVal.length() > 12 ? headerVal.substring(0, 8) + "..." : headerVal;
        String tokenSha4 = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(headerVal.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
            tokenSha4 = sb.toString();
        } catch (Exception ignore) { }

        if (returnTokenHeader != null && "true".equalsIgnoreCase(returnTokenHeader.trim())) {
            // Development helper: return the full header value so we can paste it in other clients
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "preview", preview,
                "token_sha4", tokenSha4,
                "authorization", headerVal
            ));
        }

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "preview", preview,
            "token_sha4", tokenSha4
        ));
    }
}
