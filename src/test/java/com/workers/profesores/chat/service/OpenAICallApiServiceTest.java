package com.workers.profesores.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICallApiServiceTest {

    private OpenAICallApiService service;

    @BeforeEach
    void setUp() {
        service = new OpenAICallApiService();
        // Inyectar un stub de ApiProxyService que devuelve un JSON simple
        service.setApiProxyService(new ApiProxyService() {
            @Override
            public String executeSpecCall(Map<String, Object> endpoint, String method, com.fasterxml.jackson.databind.JsonNode pathParams, com.fasterxml.jackson.databind.JsonNode query, com.fasterxml.jackson.databind.JsonNode body, String authorization) {
                // Devuelve un JSON de éxito con método y path para verificar
                String p = endpoint == null ? "null" : String.valueOf(endpoint.getOrDefault("path", "<no-path>"));
                return "{\"ok\":true,\"method\":\"" + method + "\",\"path\":\"" + p + "\"}";
            }
        });
    }

    @Test
    void processToolCalls_noToolCalls_returnsFalse() throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("tool_calls", List.of());
        List<Map<String, Object>> currentMessages = new ArrayList<>();

        Method m = OpenAICallApiService.class.getDeclaredMethod("processToolCalls", Map.class, List.class, com.workers.profesores.chat.util.RequestFlowXmlLogger.class, String.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(service, response, currentMessages, null, null);

        assertFalse(result, "processToolCalls debe devolver false cuando no hay tool_calls");
        assertTrue(currentMessages.isEmpty(), "No debe añadirse ningún mensaje");
    }

    @Test
    void executeToolCall_callApi_executesAndReturnsResult() throws Exception {
        // Construir un toolCall válido
        Map<String, Object> args = new HashMap<>();
        args.put("name", "getUsers");
        args.put("method", "GET");
        args.put("pathParams", Map.of());
        args.put("query", Map.of());
        args.put("body", Map.of());

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("name", "call_api");
        toolCall.put("arguments", args);

    // Añadir un endpoint en la whitelist que coincida con operationId 'getUsers' (usar reflexión para acceder al campo privado)
    Map<String, Object> endpoint = new HashMap<>();
    endpoint.put("operationId", "getUsers");
    endpoint.put("path", "/users");
    endpoint.put("method", "GET");
    java.lang.reflect.Field f = OpenAICallApiService.class.getDeclaredField("whitelist");
    f.setAccessible(true);
    f.set(service, List.of(endpoint));

        Method m = OpenAICallApiService.class.getDeclaredMethod("executeToolCall", Map.class, String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(service, toolCall, null);

        assertNotNull(result, "Resultado no debe ser nulo");
        assertTrue(result.contains("\"ok\":true"), "Debe retornar JSON de éxito");
        assertTrue(result.contains("\"path\":\"/users\""), "Debe incluir el path del endpoint ejecutado");
    }
}
