package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que cuando el JWT trae displayName en claims, ChatService no hace prefetch del perfil
 * y construye un perfil sintético para el prompt.
 */
public class ChatServiceProfilePrefetchTest {

    static class RecordingApiProxy extends ApiProxyService {
        public int profileCalls = 0;
        @Override
        public String executeSpecCall(Map<String, Object> endpoint, String method,
                                      com.fasterxml.jackson.databind.JsonNode pathParams,
                                      com.fasterxml.jackson.databind.JsonNode query,
                                      com.fasterxml.jackson.databind.JsonNode body,
                                      String authorization) {
            // Consideramos que una llamada a GET sin params con operationId getMiPerfil cuenta como prefetch
            Object op = endpoint == null ? null : endpoint.get("name");
            if (op != null && ("getMiPerfil".equals(op) || "usuarios.obtener_mi_perfil".equals(op))) {
                profileCalls++;
            }
            return "{\"ok\":true}";
        }
    }

    static class DummyOpenAI extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // No tool calls; solo devolver estructura mínima válida para continuar el flujo
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        }
        @Override
        public Map<String, Object> getEndpointByName(String name) {
            // El ChatService no debe solicitar endpoint de perfil cuando hay displayName
            return Map.of("name", name);
        }
        @Override
        public String renderEndpointsTable() { return ""; }
    }

    static class FixedJwtDelegationService extends JwtDelegationService {
        @Override
        public String createDelegatedAuthorizationHeader(UserClaims claims) { return null; }
    }

    @Test
    public void whenDisplayNamePresent_skipProfilePrefetch() {
        RecordingApiProxy proxy = new RecordingApiProxy();
        DummyOpenAI openai = new DummyOpenAI();
        FixedJwtDelegationService jd = new FixedJwtDelegationService();
        ChatService svc = new ChatService(openai, proxy, jd);

        List<ChatRequest.Message> incoming = List.of(new ChatRequest.Message("user", "hola"));
        UserClaims claims = new UserClaims(5L, List.of("Admin_academia"), 77, null, null, 1, "Ana Pérez");
        svc.runChat(incoming, null, "Bearer abc", claims);

        assertEquals(0, proxy.profileCalls, "No debería prefetch de perfil si ya hay displayName en claims");
    }
}
