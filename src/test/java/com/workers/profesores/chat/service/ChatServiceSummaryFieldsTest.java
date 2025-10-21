package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ChatServiceSummaryFieldsTest {

    static class DummyOpenAI_NoItems extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\"text\":\"Hola\",\"suggestions\":[\"Ver academias\"]}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        @Override
        public String callChatNoToolsWithExtras(List<Map<String, Object>> messages, Map<String,Object> responseSchemaExtras, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\\\"text\\\":\\\"Hola\\\"}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        private String quote(String s){ return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    }

    static class DummyOpenAI_WithItems_NoSummary extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\"text\":\"He encontrado 1 academia\",\"academias\":[{\"id\":1,\"nombre\":\"Demo\"}],\"suggestions\":[\"Ver detalle\"]}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        @Override
        public String callChatNoToolsWithExtras(List<Map<String, Object>> messages, Map<String,Object> responseSchemaExtras, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\\\"text\\\":\\\"He encontrado 1 academia\\\"}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        private String quote(String s){ return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    }

    static class DummyOpenAI_WithItems_WithSummary extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\"text\":\"He encontrado 2 academias\",\"academias\":[{\"id\":1,\"nombre\":\"A\"},{\"id\":2,\"nombre\":\"B\"}],\"summary_fields\":[\"nombre\",\"id\"],\"suggestions\":[\"Siguiente página\"]}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        @Override
        public String callChatNoToolsWithExtras(List<Map<String, Object>> messages, Map<String,Object> responseSchemaExtras, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            String contentJson = "{\\\"text\\\":\\\"He encontrado 2 academias\\\"}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        private String quote(String s){ return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    }

    static class NoopApiProxy extends ApiProxyService { }
    static class NoopJwtDelegation extends JwtDelegationService { }

    @Test
    public void noItems_shouldNotIncludeSummaryFields() throws Exception {
        ChatService svc = new ChatService(new DummyOpenAI_NoItems(), new NoopApiProxy(), new NoopJwtDelegation());
        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","hola")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));
        assertEquals("success", env.getStatus());
        assertNotNull(env.getData());
        assertTrue(env.getData().getItems().isEmpty());
        assertNull(env.getData().getSummaryFields(), "summaryFields must be null when there are no items");
    }

    @Test
    public void withItems_withoutSummaryFields_isAllowed() throws Exception {
        ChatService svc = new ChatService(new DummyOpenAI_WithItems_NoSummary(), new NoopApiProxy(), new NoopJwtDelegation());
        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","academias")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));
        assertEquals("success", env.getStatus());
        assertEquals("academias", env.getData().getType());
        assertEquals(1, env.getData().getItems().size());
        assertNull(env.getData().getSummaryFields(), "summaryFields may be absent when items exist");
    }

    @Test
    public void withItems_withSummaryFields_isPropagated() throws Exception {
        ChatService svc = new ChatService(new DummyOpenAI_WithItems_WithSummary(), new NoopApiProxy(), new NoopJwtDelegation());
        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","academias")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));
        assertEquals("success", env.getStatus());
        assertEquals("academias", env.getData().getType());
        assertEquals(2, env.getData().getItems().size());
        assertNotNull(env.getData().getSummaryFields());
        assertEquals(List.of("nombre","id"), env.getData().getSummaryFields());
    }
}
