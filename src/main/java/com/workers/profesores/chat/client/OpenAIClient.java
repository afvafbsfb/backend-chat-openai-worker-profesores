package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class OpenAIClient {
    @Value("${backend.debug:false}")
    private boolean debug;
    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/responses}")
    private String apiUrl;
    @Value("${openai.api.baseurl:https://api.openai.com/v1}")
    private String openaiApiBaseUrl;
    @Value("${openai.api.endpoint.responses:/responses}")
    private String openaiApiEndpointResponses;
    @Value("${openai.api.model:gpt-4o-mini}")
    private String openaiApiModel;

    private final RestTemplate restTemplate = new RestTemplate();

    public JsonNode createResponse(List<?> messages, List<?> tools) {
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] createResponse llamado con messages: " + messages + ", tools: " + tools);
        }
    Map<String, Object> body = Map.of(
        "model", openaiApiModel,
        "messages", messages,
        "tools", tools,
        "stream", false
    );
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] Payload enviado a OpenAI: " + body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = composeResponsesUrl();
        if (debug) System.out.println("[OpenAIClient][DEBUG] POST to OpenAI URL: " + url);
        JsonNode response = restTemplate.postForObject(url, entity, JsonNode.class);
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] Respuesta recibida de OpenAI: " + response);
        }
        return response;
    }

    private String composeResponsesUrl() {
        String base = openaiApiBaseUrl == null ? "https://api.openai.com/v1" : openaiApiBaseUrl.trim();
        String endpoint = openaiApiEndpointResponses == null ? "/responses" : openaiApiEndpointResponses.trim();
        if (!base.endsWith("/") && !endpoint.startsWith("/")) base = base + "/";
        return base.endsWith("/") ? base.replaceAll("/+$", "") + endpoint : base + endpoint;
    }

    // Adaptado a tools v2: reenviar mensajes + tool_outputs
    public JsonNode submitToolOutputsV2(JsonNode prevTurn, List<JsonNode> toolOutputs) {
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] submitToolOutputsV2 llamado con prevTurn: " + prevTurn + ", toolOutputs: " + toolOutputs);
        }
        // Construir el nuevo historial de mensajes
        java.util.List<Object> messages = new java.util.ArrayList<>();
        // 1. Mensajes previos (usuario)
        if (prevTurn.has("usage") && prevTurn.has("choices")) {
            // reconstruir mensajes desde el input original si es posible
            // (esto puede necesitar adaptación según el historial real)
        }
        // 2. Mensaje de tool call
        if (prevTurn.has("choices") && prevTurn.get("choices").isArray() && prevTurn.get("choices").size() > 0) {
            JsonNode choice = prevTurn.get("choices").get(0);
            if (choice.has("message")) {
                messages.add(choice.get("message"));
            }
        }
        // 3. Mensaje de tool (respuesta de la función)
        for (JsonNode toolOutput : toolOutputs) {
            java.util.Map<String, Object> toolMsg = new java.util.HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolOutput.get("tool_call_id").asText());
            toolMsg.put("content", toolOutput.get("output").asText());
            messages.add(toolMsg);
        }
    Map<String, Object> body = Map.of(
        "model", openaiApiModel,
        "messages", messages,
        "tools", List.of(),
        "stream", false
    );
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] Payload enviado a OpenAI: " + body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        JsonNode response = restTemplate.postForObject(apiUrl, entity, JsonNode.class);
        if (debug) {
            System.out.println("[OpenAIClient][DEBUG] Respuesta recibida de OpenAI: " + response);
        }
        return response;
    }
}
