
package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
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
    public org.springframework.http.ResponseEntity<String> executeWhitelistedCallWithResponse(
            Map<String, Object> endpoint,
            String methodFromModel,
            com.fasterxml.jackson.databind.JsonNode pathParams,
            com.fasterxml.jackson.databind.JsonNode query,
            com.fasterxml.jackson.databind.JsonNode body
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
    // El método fromHttpUrl está deprecado en Spring 6.2+, pero se mantiene por compatibilidad
    org.springframework.web.util.UriComponentsBuilder uri = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(baseUrl + resolvedPath);
    @SuppressWarnings("unchecked")
    java.util.List<String> expectedQuery = (java.util.List<String>) endpoint.getOrDefault("query", java.util.List.of());
        for (String q : expectedQuery) {
            if (query != null && query.has(q)) {
                uri.queryParam(q, query.get(q).asText());
            }
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (academyApiKey != null && !academyApiKey.isBlank()) {
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


    @Value("${academy.api.base-url}")
    private String baseUrl;

    @Value("${academia.api.key}")
    private String academyApiKey;

    @Value("${debug.api.proxy:false}")
    private boolean debugApiProxy;

    public String executeWhitelistedCall(
            Map<String, Object> endpoint,
            String methodFromModel,
            JsonNode pathParams,
            JsonNode query,
            JsonNode body
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
    // El método fromHttpUrl está deprecado en Spring 6.2+, pero se mantiene por compatibilidad
    UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + resolvedPath);
    @SuppressWarnings("unchecked")
    List<String> expectedQuery = (List<String>) endpoint.getOrDefault("query", List.of());
        for (String q : expectedQuery) {
            if (query != null && query.has(q)) {
                uri.queryParam(q, query.get(q).asText());
            }
        }
        HttpHeaders headers = new HttpHeaders();
        if (academyApiKey != null && !academyApiKey.isBlank()) {
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
            if (debugApiProxy) {
                System.out.println("[ApiProxyService][DEBUG] Error respuesta: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString());
            }
            throw ex;
        }
    }
}
