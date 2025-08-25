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
    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/responses}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public JsonNode createResponse(List<?> messages, List<?> tools) {
        // Construye el body según la API de OpenAI (debe usar 'messages')
        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "messages", messages,
                "tools", tools,
                "stream", false
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(apiUrl, entity, JsonNode.class);
    }

    // Adaptado a tools v2: reenviar mensajes + tool_outputs
    public JsonNode submitToolOutputsV2(JsonNode prevTurn, List<JsonNode> toolOutputs) {
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
        // Enviar a OpenAI
        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "messages", messages,
                "tools", List.of(),
                "stream", false
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(apiUrl, entity, JsonNode.class);
    }
}
