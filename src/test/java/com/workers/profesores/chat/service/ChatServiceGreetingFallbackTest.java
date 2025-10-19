package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ChatServiceGreetingFallbackTest {

    static class DummyOpenAI_EmptyMessage extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Simula un modelo que devuelve un objeto válido pero con text vacío y sin arrays
            String contentJson = "{\"text\":\"\",\"ui_suggestions\":[]}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        private String quote(String s){ return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    }

    static class NoopApiProxy extends ApiProxyService { }
    static class NoopJwtDelegation extends JwtDelegationService { }

    @Test
    public void emptyMessage_noItems_noBackendFallback() throws Exception {
        ChatService svc = new ChatService(new DummyOpenAI_EmptyMessage(), new NoopApiProxy(), new NoopJwtDelegation());
        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","hola")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));
        assertEquals("success", env.getStatus());
        // El backend ya no rellena el texto en blanco: el prompt/IA debe aportarlo
        assertNotNull(env.getMessage());
    // Nuevo contrato: no se autogeneran ui_suggestions en el fallback de saludo
    assertNotNull(env.getUiSuggestions());
    assertTrue(env.getUiSuggestions().isEmpty(), "Fallback greeting should not auto-fill ui_suggestions");
    }
}
