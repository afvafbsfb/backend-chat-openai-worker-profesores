
package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Service
public class ApiProxyService {
    // ...existing code...

    // Devuelve ResponseEntity para logging de status code (solo para pruebas)
    // Version antigua mantenida para compatibilidad
    public org.springframework.http.ResponseEntity<String> executeWhitelistedCallWithResponse(
        Map<String, Object> endpoint,
        String methodFromModel,
        com.fasterxml.jackson.databind.JsonNode pathParams,
        com.fasterxml.jackson.databind.JsonNode query,
        com.fasterxml.jackson.databind.JsonNode body
    ) throws Exception {
    return executeWhitelistedCallWithResponse(endpoint, methodFromModel, pathParams, query, body, null);
    }

    // Nueva firma que acepta Authorization header (Bearer token). Si authorization != null y comienza con "Bearer ",
    // se usará como header Authorization; en otro caso, si academyApiKey está definida, se usará X-API-Key para compatibilidad.
    public org.springframework.http.ResponseEntity<String> executeWhitelistedCallWithResponse(
        Map<String, Object> endpoint,
        String methodFromModel,
        com.fasterxml.jackson.databind.JsonNode pathParams,
        com.fasterxml.jackson.databind.JsonNode query,
        com.fasterxml.jackson.databind.JsonNode body,
        String authorization
    ) throws Exception {
        if (debugApiProxy) {
            System.out.println("[ApiProxyService][DEBUG] executeWhitelistedCallWithResponse llamado con endpoint=" + endpoint + ", methodFromModel=" + methodFromModel + ", pathParams=" + pathParams + ", query=" + query + ", body=" + body);
        }
        if (debugApiProxy && authorization != null) {
            try {
                String a = authorization.trim();
                String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(a.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                System.out.println("[ApiProxyService][DEBUG] Authorization preview=" + preview + ", token_sha4=" + sb.toString());
            } catch (Exception ignore) { }
        }
    // String name = (String) endpoint.get("name"); // No se usa
        String wlMethod = ((String) endpoint.get("method")).toUpperCase();
        String finalMethod = wlMethod;
        String rawPath = (String) endpoint.get("path");
        String resolvedPath = rawPath;
    @SuppressWarnings("unchecked")
    java.util.List<String> expectedPathParams = (java.util.List<String>) endpoint.getOrDefault("pathParams", java.util.List.of());
        if (!expectedPathParams.isEmpty()) {
            for (String p : expectedPathParams) {
                String val = pathParams != null && pathParams.has(p) ? pathParams.get(p).asText() : null;
                if (val == null || val.isBlank()) throw new IllegalArgumentException("Falta pathParam: " + p);
                resolvedPath = resolvedPath.replace("{" + p + "}", val);
            }
        }
    // Usar fromUriString para evitar uso de API deprecada
    org.springframework.web.util.UriComponentsBuilder uri = org.springframework.web.util.UriComponentsBuilder.fromUriString(baseUrl + resolvedPath);
    @SuppressWarnings("unchecked")
    java.util.List<String> expectedQuery = (java.util.List<String>) endpoint.getOrDefault("query", java.util.List.of());
        for (String q : expectedQuery) {
            if (query != null && query.has(q)) {
                uri.queryParam(q, query.get(q).asText());
            }
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        // Preferir Authorization Bearer token si se pasa; si no, usar X-API-Key para compatibilidad
        String sanitizedAuth = sanitizeAuthorizationHeader(authorization);
        if (sanitizedAuth != null && sanitizedAuth.toLowerCase().startsWith("bearer ")) {
            headers.set("Authorization", sanitizedAuth);
        } else if (academyApiKey != null && !academyApiKey.isBlank()) {
            headers.set("x-api-key", academyApiKey);
        }
        org.springframework.http.HttpEntity<String> entity;
        if ("GET".equals(finalMethod) || "DELETE".equals(finalMethod)) {
            headers.set("accept", "*/*");
            entity = new org.springframework.http.HttpEntity<>(null, headers);
        } else {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("accept", "*/*");
            String jsonBody = (body == null || body.isNull()) ? "{}" : body.toString();
            entity = new org.springframework.http.HttpEntity<>(jsonBody, headers);
        }
        if (debugApiProxy) {
            System.out.println("[ApiProxyService][DEBUG] Llamando a Academia: " + finalMethod + " " + uri.build(true).toUri());
            // Mask Authorization value when printing headers for diagnostics
            try {
                org.springframework.http.HttpHeaders copy = new org.springframework.http.HttpHeaders();
                copy.putAll(headers);
                if (copy.containsKey("Authorization")) {
                    List<String> vals = copy.get("Authorization");
                    if (vals != null && !vals.isEmpty()) {
                        String v = vals.get(0);
                        String masked = v.replaceAll("(?i)bearer\\s+([A-Za-z0-9\\-_.=]+)", "Bearer *****");
                        copy.set("Authorization", masked);
                    }
                }
                System.out.println("[ApiProxyService][DEBUG] Headers: " + copy);
            } catch (Exception ex) {
                System.out.println("[ApiProxyService][DEBUG] Headers: " + headers);
            }
            if (!"GET".equals(finalMethod) && !"DELETE".equals(finalMethod)) {
                System.out.println("[ApiProxyService][DEBUG] Body: " + entity.getBody());
            }
        }
        org.springframework.http.ResponseEntity<String> resp;
        switch (finalMethod) {
            case "GET":
                resp = restTemplate.exchange(uri.build(true).toUri(), org.springframework.http.HttpMethod.GET, entity, String.class);
                break;
            case "POST":
                resp = restTemplate.exchange(uri.build(true).toUri(), org.springframework.http.HttpMethod.POST, entity, String.class);
                break;
            case "PUT":
                resp = restTemplate.exchange(uri.build(true).toUri(), org.springframework.http.HttpMethod.PUT, entity, String.class);
                break;
            case "DELETE":
                resp = restTemplate.exchange(uri.build(true).toUri(), org.springframework.http.HttpMethod.DELETE, entity, String.class);
                break;
            default:
                throw new IllegalArgumentException("Método no soportado: " + finalMethod);
        }
        if (debugApiProxy) {
            System.out.println("[ApiProxyService][DEBUG] Respuesta: " + resp.getStatusCode() + " - " + resp.getBody());
        }
        return resp;
    }
    private final RestTemplate restTemplate = new RestTemplate();
    // private final ObjectMapper om = new ObjectMapper(); // No se usa


    @Value("${academia.api.baseurl:http://localhost:5000}")
    private String baseUrl;

    @Value("${academia.api.key:}")
    private String academyApiKey;

    @Value("${debug.api.proxy:false}")
    private boolean debugApiProxy;

    // Helper: sanitize Authorization header (remove surrounding quotes, trim whitespace,
    // collapse repeated Bearer prefixes). Returns null if input is null or empty after trim.
    private String sanitizeAuthorizationHeader(String authorization) {
        if (authorization == null) return null;
        String a = authorization.trim();
        if (a.isEmpty()) return null;
        // Remove surrounding double quotes if present
        if (a.length() >= 2 && a.startsWith("\"") && a.endsWith("\"")) {
            a = a.substring(1, a.length() - 1).trim();
        }
        // Collapse repeated Bearer prefixes (case-insensitive) into a single 'Bearer '
        // e.g. 'Bearer Bearer abc' -> 'Bearer abc'
        a = a.replaceAll("(?i)^(?:bearer\\s+)+", "Bearer ");
        // IMPORTANT: do NOT auto-prefix a raw JWT with 'Bearer ' here — that can corrupt
        // tokens if the caller intentionally provided a different format. If the header
        // does not start with 'Bearer ', we leave it unchanged. In debug mode we emit
        // a small notice so operators can see headers missing the prefix.
        if (!a.toLowerCase().startsWith("bearer ") && debugApiProxy) {
            try {
                String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                System.out.println("[ApiProxyService][DEBUG] Authorization header does not start with 'Bearer ': preview=" + preview + " (left unchanged)");
            } catch (Exception ignore) { }
        }
        return a;
    }

    @Autowired(required = false)
    private com.workers.profesores.chat.auth.JwtVerifier jwtVerifier;

    @Autowired(required = false)
    private com.workers.profesores.chat.auth.AuthorizationService authorizationService;

    @Autowired(required = false)
    private com.workers.profesores.chat.service.OpenAICallApiService openAICallApiService;

    // Versión antigua para compatibilidad
    public String executeWhitelistedCall(
        Map<String, Object> endpoint,
        String methodFromModel,
        JsonNode pathParams,
        JsonNode query,
        JsonNode body
    ) throws Exception {
    return executeWhitelistedCall(endpoint, methodFromModel, pathParams, query, body, null);
    }

    /**
     * New alias: execute a call using the spec endpoints metadata. Kept for clarity when migrating away from the term "whitelist".
     */
    public String executeSpecCall(
        Map<String, Object> endpoint,
        String methodFromModel,
        JsonNode pathParams,
        JsonNode query,
        JsonNode body,
        String authorization
    ) throws Exception {
        return executeWhitelistedCall(endpoint, methodFromModel, pathParams, query, body, authorization);
    }

    // Nueva firma que acepta Authorization header y la propaga
    public String executeWhitelistedCall(
        Map<String, Object> endpoint,
        String methodFromModel,
        JsonNode pathParams,
        JsonNode query,
        JsonNode body,
        String authorization
    ) throws Exception {
        if (debugApiProxy) {
            System.out.println("[ApiProxyService][DEBUG] executeWhitelistedCall llamado con endpoint=" + endpoint + ", methodFromModel=" + methodFromModel + ", pathParams=" + pathParams + ", query=" + query + ", body=" + body);
        }
        if (debugApiProxy && authorization != null) {
            try {
                String a = authorization.trim();
                String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                System.out.println("[ApiProxyService][DEBUG] Authorization preview=" + preview + ", token_sha4=" + sb.toString());
            } catch (Exception ignore) { }
        }
    // String name = (String) endpoint.get("name"); // No se usa
        String wlMethod = ((String) endpoint.get("method")).toUpperCase();
        String finalMethod = wlMethod;
        String rawPath = (String) endpoint.get("path");
        String resolvedPath = rawPath;
    @SuppressWarnings("unchecked")
    List<String> expectedPathParams = (List<String>) endpoint.getOrDefault("pathParams", List.of());
        if (!expectedPathParams.isEmpty()) {
            for (String p : expectedPathParams) {
                String val = pathParams != null && pathParams.has(p) ? pathParams.get(p).asText() : null;
                if (val == null || val.isBlank()) throw new IllegalArgumentException("Falta pathParam: " + p);
                resolvedPath = resolvedPath.replace("{" + p + "}", val);
            }
        }
    // Usar fromUriString para evitar uso de API deprecada
    UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + resolvedPath);
    @SuppressWarnings("unchecked")
    List<String> expectedQuery = (List<String>) endpoint.getOrDefault("query", List.of());
        for (String q : expectedQuery) {
            if (query != null && query.has(q)) {
                uri.queryParam(q, query.get(q).asText());
            }
        }
        HttpHeaders headers = new HttpHeaders();
    // Si recibimos Authorization, validamos claims y aplicamos políticas
        String sanitizedAuth = sanitizeAuthorizationHeader(authorization);
        if (sanitizedAuth != null && sanitizedAuth.toLowerCase().startsWith("bearer ") && jwtVerifier != null && authorizationService != null) {
            com.workers.profesores.chat.auth.UserClaims claims;
            try {
                claims = jwtVerifier.verify(sanitizedAuth);
            } catch (Exception ex) {
                throw new RuntimeException("Invalid token: " + ex.getMessage());
            }
            // Validar permiso según la política
            authorizationService.checkAllowed(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, methodFromModel, pathParams, query);
            // Transformar si la política lo requiere (por ejemplo: collection -> item)
            java.util.Map<String, Object> transform = authorizationService.transformIfNeeded(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, pathParams, query);
            if (transform != null && transform.containsKey("transform_to") && openAICallApiService != null) {
                String newEpName = String.valueOf(transform.get("transform_to"));
                Map<String, Object> newEp = openAICallApiService.getEndpointByName(newEpName);
                if (newEp == null) throw new RuntimeException("Transform error: endpoint metadata not found for " + newEpName);
                endpoint = newEp;
                pathParams = (com.fasterxml.jackson.databind.JsonNode) transform.getOrDefault("pathParams", pathParams);
                query = (com.fasterxml.jackson.databind.JsonNode) transform.getOrDefault("query", query);
            } else {
                java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> sanitized = authorizationService.sanitizeParams(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, pathParams, query);
                pathParams = sanitized.getOrDefault("pathParams", pathParams);
                query = sanitized.getOrDefault("query", query);
            }
            headers.set("Authorization", sanitizedAuth);
        } else if (sanitizedAuth != null && sanitizedAuth.toLowerCase().startsWith("bearer ")) {
            // Si no disponemos de verifier/authorizationService, al menos pasar el header
            headers.set("Authorization", sanitizedAuth);
        } else if (academyApiKey != null && !academyApiKey.isBlank()) {
            headers.set("x-api-key", academyApiKey);
        }
        HttpEntity<String> entity;
        if ("GET".equals(finalMethod) || "DELETE".equals(finalMethod)) {
            headers.set("accept", "*/*");
            entity = new HttpEntity<>(null, headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("accept", "*/*");
            String jsonBody = (body == null || body.isNull()) ? "{}" : body.toString();
            entity = new HttpEntity<>(jsonBody, headers);
        }
        if (debugApiProxy) {
            System.out.println("[ApiProxyService][DEBUG] Llamando a Academia: " + finalMethod + " " + uri.build(true).toUri());
            System.out.println("[ApiProxyService][DEBUG] Headers: " + headers);
            if (!"GET".equals(finalMethod) && !"DELETE".equals(finalMethod)) {
                System.out.println("[ApiProxyService][DEBUG] Body: " + entity.getBody());
            }
        }
        // Retry logic for transient network errors. We do NOT retry on HTTP status (4xx/5xx) errors.
        final int maxAttempts = 3;
        final long[] backoffs = new long[] {200L, 500L, 1000L}; // ms
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> resp = switch (finalMethod) {
                    case "GET"    -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.GET, entity, String.class);
                    case "POST"   -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.POST, entity, String.class);
                    case "PUT"    -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.PUT, entity, String.class);
                    case "DELETE" -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.DELETE, entity, String.class);
                    default       -> throw new IllegalArgumentException("Método no soportado: " + finalMethod);
                };
                if (debugApiProxy) {
                    System.out.println("[ApiProxyService][DEBUG] Respuesta: " + resp.getStatusCode() + " - " + resp.getBody());
                }
                return resp.getBody() == null ? "{}" : resp.getBody();
            } catch (org.springframework.web.client.HttpStatusCodeException ex) {
                // No reintentamos en errores HTTP; devolvemos detalle estructurado inmediatamente.
                if (debugApiProxy) {
                    System.out.println("[ApiProxyService][DEBUG] Error respuesta HTTP (no retry): " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString());
                    ex.printStackTrace(System.out);
                }
                int status = ex.getStatusCode() != null ? ex.getStatusCode().value() : -1;
                String respBody = ex.getResponseBodyAsString();
                String safeBody = respBody == null ? "" : respBody.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                String result = "{\"error\":\"http_error\",\"status\":" + status + ",\"body\":\"" + safeBody + "\"}";
                return result;
            } catch (Exception ex) {
                // Errores de red, tiempo de espera, connection refused, etc. Retry.
                if (debugApiProxy) {
                    System.out.println("[ApiProxyService][DEBUG] Exception calling academy API (attempt " + attempt + "): " + ex.getMessage());
                    ex.printStackTrace(System.out);
                }
                boolean isLast = attempt == maxAttempts;
                if (!isLast) {
                    long backoff = backoffs[Math.min(attempt - 1, backoffs.length - 1)];
                    if (debugApiProxy) System.out.println("[ApiProxyService][DEBUG] Retrying after " + backoff + "ms (attempt " + (attempt + 1) + ")");
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (debugApiProxy) System.out.println("[ApiProxyService][DEBUG] Sleep interrupted while backing off");
                    }
                    continue; // next attempt
                }
                // Last attempt failed: normalize to JSON error
                String safe = ex.getMessage() == null ? "" : ex.getMessage().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                String result = "{\"error\":\"exception\",\"message\":\"" + safe + "\"}";
                return result;
            }
        }
        // Should not reach here, but return a defensive error
        return "{\"error\":\"exception\",\"message\":\"unknown_error\"}";
    }
}
