package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SummaryFieldsMax3ItemsTest {

    static class DummyOpenAI_ListNoSummary extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Devuelve un contenido con muchos items y sin summary_fields
            StringBuilder arr = new StringBuilder("[");
            for (int i = 1; i <= 6; i++) {
                if (i > 1) arr.append(',');
                arr.append("{\"id\":").append(i).append(",\"nombre\":\"Usuario ").append(i).append("\"}");
            }
            arr.append("]");
            String contentJson = "{\"text\":\"He encontrado usuarios\",\"usuarios\":" + arr + ",\"suggestions\":[\"Siguiente página\"]}";
            return "{\"choices\":[{\"message\":{\"content\":" + quote(contentJson) + "}}]}";
        }
        private String quote(String s){ return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    }

    static class RecordingApiProxy extends ApiProxyService {
        public int callCount = 0;
        @Override
        public String executeSpecCall(Map<String, Object> endpoint, String methodFromModel, com.fasterxml.jackson.databind.JsonNode pathParams, com.fasterxml.jackson.databind.JsonNode query, com.fasterxml.jackson.databind.JsonNode body, String authorization) {
            callCount++;
            return "{}";
        }
    }

    static class NoopJwtDelegation extends JwtDelegationService { }

    @Test
    public void noExtraInspectionOrCalls_whenNoSummaryFieldsProvided() throws Exception {
        DummyOpenAI_ListNoSummary openai = new DummyOpenAI_ListNoSummary();
        RecordingApiProxy proxy = new RecordingApiProxy();
        NoopJwtDelegation jd = new NoopJwtDelegation();
        ChatService svc = new ChatService(openai, proxy, jd);

        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","usuarios")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));

        assertEquals("success", env.getStatus());
        assertEquals("usuarios", env.getData().getType());
        assertEquals(6, env.getData().getItems().size());
        assertNull(env.getData().getSummaryFields(), "Sin summary_fields del modelo, no derivamos ni llamamos a API adicional");
        assertEquals(0, proxy.callCount, "No debe haber llamadas adicionales a la API solo para decidir summary_fields");
    }
}
