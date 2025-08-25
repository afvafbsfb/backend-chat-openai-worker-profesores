package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.List;
import java.util.Map;

@Service
public class ApiProxyService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper om = new ObjectMapper();


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
        String name = (String) endpoint.get("name");
        String wlMethod = ((String) endpoint.get("method")).toUpperCase();
        String finalMethod = wlMethod;
        String rawPath = (String) endpoint.get("path");
        String resolvedPath = rawPath;
        List<String> expectedPathParams = (List<String>) endpoint.getOrDefault("pathParams", List.of());
        if (!expectedPathParams.isEmpty()) {
            for (String p : expectedPathParams) {
                String val = pathParams != null && pathParams.has(p) ? pathParams.get(p).asText() : null;
                if (val == null || val.isBlank()) throw new IllegalArgumentException("Falta pathParam: " + p);
                resolvedPath = resolvedPath.replace("{" + p + "}", val);
            }
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + resolvedPath);
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
