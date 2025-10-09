
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
        if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
            headers.set("Authorization", authorization);
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
            System.out.println("[ApiProxyService][DEBUG] Headers: " + headers);
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
        if (authorization != null && authorization.toLowerCase().startsWith("bearer ") && jwtVerifier != null && authorizationService != null) {
            com.workers.profesores.chat.auth.UserClaims claims;
            try {
                claims = jwtVerifier.verify(authorization);
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
            headers.set("Authorization", authorization);
        } else if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
            // Si no disponemos de verifier/authorizationService, al menos pasar el header
            headers.set("Authorization", authorization);
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
            // Devuelve siempre un JSON estructurado con status y body para que el llamador lo maneje fácilmente
            if (debugApiProxy) {
                System.out.println("[ApiProxyService][DEBUG] Error respuesta: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString());
            }
            int status = ex.getStatusCode() != null ? ex.getStatusCode().value() : -1;
            String respBody = ex.getResponseBodyAsString();
            String safeBody = respBody == null ? "" : respBody.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String result = "{\"error\":\"http_error\",\"status\":" + status + ",\"body\":\"" + safeBody + "\"}";
            return result;
        } catch (Exception ex) {
            // Errores de red, tiempo de espera, etc. Normalizar también a JSON
            if (debugApiProxy) {
                System.out.println("[ApiProxyService][DEBUG] Exception calling academy API: " + ex.getMessage());
            }
            String safe = ex.getMessage() == null ? "" : ex.getMessage().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String result = "{\"error\":\"exception\",\"message\":\"" + safe + "\"}";
            return result;
        }
    }
}
