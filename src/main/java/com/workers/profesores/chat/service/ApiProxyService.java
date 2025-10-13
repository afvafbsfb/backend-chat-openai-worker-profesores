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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Service
public class ApiProxyService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${academia.api.baseurl:http://localhost:5000}")
    private String baseUrl;

    @Value("${backend.debug:false}")
    private boolean debugApiProxy;

    @Value("${dump.secrets:false}")
    private boolean dumpSecrets;

    private static final Logger logger = LoggerFactory.getLogger(ApiProxyService.class);

    @Autowired(required = false)
    private com.workers.profesores.chat.auth.JwtVerifier jwtVerifier;

    @Autowired(required = false)
    private com.workers.profesores.chat.auth.AuthorizationService authorizationService;

    @Autowired(required = false)
    private com.workers.profesores.chat.service.OpenAICallApiService openAICallApiService;

    // Devuelve ResponseEntity para logging de status code (compat)
    public ResponseEntity<String> executeWhitelistedCallWithResponse(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body
    ) throws Exception {
        return executeWhitelistedCallWithResponse(endpoint, methodFromModel, pathParams, query, body, null);
    }

    // Overload that accepts an Authorization header (delegated token). We forward it when present.
    public ResponseEntity<String> executeWhitelistedCallWithResponse(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body,
            String authorization
    ) throws Exception {
        return executeWhitelistedCallWithResponse(endpoint, methodFromModel, pathParams, query, body, authorization, null);
    }

    // New overload that accepts an xmlLogger so ApiProxyService can add steps to the HTML trace
    public ResponseEntity<String> executeWhitelistedCallWithResponse(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body,
            String authorization,
            com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger
    ) throws Exception {
        if (debugApiProxy) {
            logger.debug("[ApiProxyService] executeWhitelistedCallWithResponse called endpoint={} method={} pathParams={} query={} body={}", endpoint, methodFromModel, pathParams, query, body);
        }

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

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + resolvedPath);
        @SuppressWarnings("unchecked")
        List<String> expectedQuery = (List<String>) endpoint.getOrDefault("query", List.of());
        for (String q : expectedQuery) {
            if (query != null && query.has(q)) {
                uri.queryParam(q, query.get(q).asText());
            }
        }

        HttpHeaders headers = new HttpHeaders();
    String sanitizedAuth = sanitizeAuthorizationHeader(authorization);

        // We REQUIRE a delegated Authorization header (signed by chat-backend) for proxied calls.
        if (sanitizedAuth == null || !sanitizedAuth.toLowerCase().startsWith("bearer ")) {
            throw new RuntimeException("Delegated authorization required for proxied API calls");
        }
        if (jwtVerifier == null || authorizationService == null) {
            throw new RuntimeException("Authorization infrastructure not available to verify delegated token");
        }
        com.workers.profesores.chat.auth.UserClaims claims;
        // Diagnostic dump: print sanitized auth and token fingerprint when enabled
        try {
            if ((dumpSecrets || debugApiProxy) && sanitizedAuth != null) {
                String tokenOnly = sanitizedAuth.replaceAll("(?i)^Bearer\\s+", "").replaceAll("[\\r\\n]+", "").trim();
                String preview = tokenOnly.length() > 32 ? tokenOnly.substring(0, 32) + "..." : tokenOnly;
                String sha4 = "";
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(tokenOnly.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                    sha4 = sb.toString();
                } catch (Exception ex) {
                    // ignore digest errors
                }
                System.out.println("[API-PROXY][DUMP] sanitizedAuth_preview=" + preview);
                System.out.println("[API-PROXY][DUMP] token_sha4=" + sha4 + " token_len=" + (tokenOnly == null ? 0 : tokenOnly.length()));
            }
        } catch (Exception ignore) { }
        try {
            claims = jwtVerifier.verify(sanitizedAuth);
        } catch (Exception ex) {
            throw new RuntimeException("Invalid delegated token: " + ex.getMessage());
        }
        // Delegated tokens must include actor=chat-backend (created by JwtDelegationService)
        if (claims == null || claims.actor == null || !"chat-backend".equals(claims.actor)) {
            throw new RuntimeException("Provided Authorization is not a delegated token from chat-backend");
        }

        // With a validated delegated token, enforce authorization policies and potential transforms
        authorizationService.checkAllowed(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, methodFromModel, pathParams, query);
        java.util.Map<String, Object> transform = authorizationService.transformIfNeeded(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, pathParams, query);
        if (transform != null && transform.containsKey("transform_to") && openAICallApiService != null) {
            String newEpName = String.valueOf(transform.get("transform_to"));
            Map<String, Object> newEp = openAICallApiService.getEndpointByName(newEpName);
            if (newEp == null) throw new RuntimeException("Transform error: endpoint metadata not found for " + newEpName);
            endpoint = newEp;
            pathParams = (JsonNode) transform.getOrDefault("pathParams", pathParams);
            query = (JsonNode) transform.getOrDefault("query", query);
        } else {
            java.util.Map<String, JsonNode> sanitized = authorizationService.sanitizeParams(new com.workers.profesores.chat.auth.UserClaims(claims.usuarioId, claims.roles, claims.academiaId, claims.profesorUsuarioId), endpoint, pathParams, query);
            pathParams = sanitized.getOrDefault("pathParams", pathParams);
            query = sanitized.getOrDefault("query", query);
        }

        // Forward delegated Authorization when present. Do NOT fallback to any static API key.
        if (sanitizedAuth != null && sanitizedAuth.toLowerCase().startsWith("bearer ")) {
            headers.set("Authorization", sanitizedAuth);
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
            logger.debug("[ApiProxyService] Calling Academy: {} {}", finalMethod, uri.build(true).toUri());
            try {
                HttpHeaders copy = new HttpHeaders();
                copy.putAll(headers);
                if (copy.containsKey("Authorization")) {
                    List<String> vals = copy.get("Authorization");
                    if (vals != null && !vals.isEmpty()) {
                        String v = vals.get(0);
                        String masked = v.replaceAll("(?i)bearer\\s+([A-Za-z0-9\\-_.=]+)", "Bearer *****");
                        copy.set("Authorization", masked);
                    }
                }
                logger.debug("[ApiProxyService] Headers: {}", copy);
            } catch (Exception ex) {
                logger.debug("[ApiProxyService] Headers: {}", headers);
            }
            if (!"GET".equals(finalMethod) && !"DELETE".equals(finalMethod)) {
                logger.debug("[ApiProxyService] Body: {}", entity.getBody());
            }
            if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Llamando a Academia: " + finalMethod + " " + uri.build(true).toUri());
        }

        final int maxAttempts = 3;
        final long[] backoffs = new long[]{200L, 500L, 1000L};

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> resp = switch (finalMethod) {
                    case "GET" -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.GET, entity, String.class);
                    case "POST" -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.POST, entity, String.class);
                    case "PUT" -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.PUT, entity, String.class);
                    case "DELETE" -> restTemplate.exchange(uri.build(true).toUri(), HttpMethod.DELETE, entity, String.class);
                    default -> throw new IllegalArgumentException("Método no soportado: " + finalMethod);
                };
                String respBody = resp.getBody();
                String respSnippet = respBody == null ? "" : (respBody.length() > 300 ? respBody.substring(0,300) + "..." : respBody);
                if (debugApiProxy) {
                    logger.debug("[ApiProxyService] Response: {} - {}", resp.getStatusCode(), respSnippet);
                }
                if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Respuesta status=" + (resp.getStatusCode() == null ? "null" : resp.getStatusCode().toString()) + " bodySnippet=" + (respBody == null ? "" : (respBody.length() > 200 ? respBody.substring(0,200) + "..." : respBody)));
                return resp;
            } catch (org.springframework.web.client.HttpStatusCodeException ex) {
                if (debugApiProxy) {
                    logger.debug("[ApiProxyService] HTTP error (no retry): {} - {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                    ex.printStackTrace(System.out);
                }
                int status = ex.getStatusCode() != null ? ex.getStatusCode().value() : -1;
                String respBody = ex.getResponseBodyAsString();
                String safeBody = respBody == null ? "" : respBody.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                String result = "{\"error\":\"http_error\",\"status\":" + status + ",\"body\":\"" + safeBody + "\"}";
                return new ResponseEntity<>(result, HttpStatus.valueOf(Math.max(0, status))); 
            } catch (Exception ex) {
                if (debugApiProxy) {
                    logger.debug("[ApiProxyService] Exception calling academy API (attempt {}) : {}", attempt, ex.getMessage());
                    ex.printStackTrace(System.out);
                }
                boolean isLast = attempt == maxAttempts;
                if (!isLast) {
                    long backoff = backoffs[Math.min(attempt - 1, backoffs.length - 1)];
                    if (debugApiProxy) logger.debug("[ApiProxyService] Retrying after {}ms (attempt {})", backoff, (attempt + 1));
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (debugApiProxy) logger.debug("[ApiProxyService] Sleep interrupted while backing off");
                    }
                    continue;
                }
                String safe = ex.getMessage() == null ? "" : ex.getMessage().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                String result = "{\"error\":\"exception\",\"message\":\"" + safe + "\"}";
                return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        return new ResponseEntity<>("{\"error\":\"exception\",\"message\":\"unknown_error\"}", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // Convenience wrappers for older signatures
    public String executeWhitelistedCall(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body
    ) throws Exception {
        return executeWhitelistedCall(endpoint, methodFromModel, pathParams, query, body, null);
    }

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

    // New overload that accepts an xmlLogger for tracing and returns the body string
    public String executeSpecCall(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body,
            String authorization,
            com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger
    ) throws Exception {
        // Delegate to the 6-arg executeSpecCall so subclasses/mocks that override it are respected.
        return executeSpecCall(endpoint, methodFromModel, pathParams, query, body, authorization);
    }

    public String executeWhitelistedCall(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body,
            String authorization
    ) throws Exception {
        ResponseEntity<String> resp = executeWhitelistedCallWithResponse(endpoint, methodFromModel, pathParams, query, body, authorization);
        return resp.getBody() == null ? "{}" : resp.getBody();
    }

    // Helper: sanitize Authorization header
    private String sanitizeAuthorizationHeader(String authorization) {
        if (authorization == null) return null;
        // Remove surrounding quotes and trim
        String a = authorization;
        if (a.length() >= 2 && a.startsWith("\"") && a.endsWith("\"")) {
            a = a.substring(1, a.length() - 1);
        }
        // Remove non-printable characters and normalize whitespace
        a = a.replaceAll("[\\r\\n\\t]+", "").trim();
        // Collapse multiple 'Bearer ' prefixes to a single one and ensure proper capitalization
        a = a.replaceAll("(?i)^(?:bearer\\s+)+", "Bearer ");
        // Remove any stray non-printable characters inside the token
        a = a.replaceAll("[^\\x20-\\x7E]", "");
        if (a.isEmpty()) return null;
        if (!a.toLowerCase().startsWith("bearer ") && debugApiProxy) {
            try {
                String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                logger.debug("[ApiProxyService] Authorization header does not start with 'Bearer ': preview={} (left unchanged)", preview);
            } catch (Exception ignore) { }
        }
        return a;
    }
}

