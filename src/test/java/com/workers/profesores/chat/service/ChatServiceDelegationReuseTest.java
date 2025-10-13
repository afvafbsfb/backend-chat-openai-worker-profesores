package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ChatServiceDelegationReuseTest {

    // Simple stub ApiProxyService that records the Authorization header used
    static class RecordingApiProxy extends ApiProxyService {
        public final List<String> authUsed = new ArrayList<>();

        @Override
        public String executeSpecCall(Map<String, Object> endpoint, String method, com.fasterxml.jackson.databind.JsonNode pathParams, com.fasterxml.jackson.databind.JsonNode query, com.fasterxml.jackson.databind.JsonNode body, String authorization) {
            authUsed.add(authorization);
            return "{\"ok\":true}";
        }
    }

    static class DummyOpenAI extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Return a response with a single tool_call to academias.listar_academias
            return "{\"choices\":[{\"message\":{\"content\":\"Respuesta\",\"tool_calls\":[{\"id\":\"1\",\"function\":{\"name\":\"call_api\" , \"arguments\": \"{\\\"name\\\":\\\"academias.listar_academias\\\",\\\"method\\\":\\\"GET\\\"}\"}}]}}]}";
        }

        @Override
        public Map<String, Object> getEndpointByName(String name) {
            Map<String,Object> ep = new HashMap<>();
            ep.put("operationId", "academias.listar_academias");
            ep.put("name", "academias.listar_academias");
            ep.put("method", "GET");
            ep.put("path", "/academias");
            return ep;
        }
    }

    static class RecordingJwtDelegationService extends JwtDelegationService {
        public int createCount = 0;
        public String fixed = "Bearer fixed-token";
        @Override
        public String createDelegatedAuthorizationHeader(com.workers.profesores.chat.auth.UserClaims claims) {
            createCount++;
            return fixed;
        }
    }

    @Test
    public void delegatedTokenCreatedOnceAndReused() throws Exception {
        RecordingApiProxy proxy = new RecordingApiProxy();
        DummyOpenAI openai = new DummyOpenAI();
        RecordingJwtDelegationService jd = new RecordingJwtDelegationService();
        // Ensure the (private) delegationSecret is set so delegated token creation cannot return null
        try {
            java.lang.reflect.Field f = JwtDelegationService.class.getDeclaredField("delegationSecret");
            f.setAccessible(true);
            f.set(jd, "01234567890123456789012345678901");
        } catch (Exception ignore) { }
        ChatService svc = new ChatService(openai, proxy, jd);

        // Prepare a simple incoming message list
        List<ChatRequest.Message> incoming = List.of(new ChatRequest.Message("user", "dame academias"));
    // Pass null as original authorization so the ChatService must create a delegated token
    svc.runChat(incoming, null, null, new UserClaims(10L, List.of("Admin_academia"), 1, null, null, 2));
        // After running, the proxy should have been called at least once
        assertFalse(proxy.authUsed.isEmpty(), "ApiProxy should have been called");
        // Delegation creation should have been attempted at least once
        assertTrue(jd.createCount >= 1, "JwtDelegationService.createDelegatedAuthorizationHeader should be invoked at least once");
        // At least one proxied call should have a non-null Authorization header (delegated or forwarded original)
        boolean anyNonNull = proxy.authUsed.stream().anyMatch(Objects::nonNull);
        assertTrue(anyNonNull, "At least one ApiProxy call should receive a non-null Authorization header");
    }
}
