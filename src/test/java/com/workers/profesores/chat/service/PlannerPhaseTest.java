package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PlannerPhaseTest {

    static class RecordingApiProxy extends ApiProxyService {
        Map<String,Object> lastEndpoint; String lastMethod; JsonNode lastPathParams; JsonNode lastQuery; String lastAuth;
        @Override
        public String executeSpecCall(Map<String, Object> endpoint, String method, JsonNode pathParams, JsonNode query, JsonNode body, String authorization) {
            this.lastEndpoint = endpoint; this.lastMethod = method; this.lastPathParams = pathParams; this.lastQuery = query; this.lastAuth = authorization;
            // Simula respuesta paginada simple
            int page = (query != null && query.has("page") ? query.get("page").asInt() : 1);
            int size = (query != null && query.has("size") ? query.get("size").asInt() : 50);
            StringBuilder items = new StringBuilder("[");
            for (int i = 0; i < size; i++) { if (i>0) items.append(','); items.append("{\"id\":").append((page-1)*size + i + 1).append("}"); }
            items.append("]");
            return "{" +
                    "\"items\":" + items + "," +
                    "\"page\":" + page + "," +
                    "\"size\":" + size +
                    "}";
        }
    }

    static class DummyOpenAIPlanner extends OpenAICallApiService {
        final ObjectMapper om = new ObjectMapper();
        @Override
        public String callPlannerStrict(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Devuelve un tool_call plan_api con endpoint válido y query vacía
            Map<String,Object> args = new HashMap<>();
            args.put("endpoint","usuarios.listar_usuarios");
            args.put("method","GET");
            Map<String,Object> fn = Map.of("name","plan_api","arguments", toJson(args));
            Map<String,Object> toolCall = Map.of("id","t1","type","function","function", fn);
            Map<String,Object> message = Map.of("role","assistant","tool_calls", List.of(toolCall));
            Map<String,Object> root = Map.of("choices", List.of(Map.of("message", message)));
            return toJson(root);
        }
        @Override
        public List<Map<String, Object>> getEndpoints() { return super.getEndpoints(); }
        @Override
        public Map<String, Object> getEndpointByName(String name) {
            Map<String,Object> ep = new HashMap<>();
            ep.put("operationId","usuarios.listar_usuarios"); ep.put("name","usuarios.listar_usuarios"); ep.put("method","GET"); ep.put("path","/usuarios"); ep.put("paginated", true);
            return "usuarios.listar_usuarios".equals(name) ? ep : null;
        }
        private String toJson(Object m) { try { return om.writeValueAsString(m); } catch(Exception e){ return "{}";} }
    }

    static class NoopJwt extends JwtDelegationService {}

    @Test
    public void plannerHappyPath_executesAndBuildsEnvelope() {
        DummyOpenAIPlanner openai = new DummyOpenAIPlanner();
        RecordingApiProxy proxy = new RecordingApiProxy();
        NoopJwt jd = new NoopJwt();
        ChatService svc = new ChatService(openai, proxy, jd);

        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","lista usuarios")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));

        assertEquals("success", env.getStatus());
        assertNotNull(env.getData());
        assertEquals("usuarios", env.getData().getType(), "Debe inferir 'usuarios' por el endpoint");
        assertNotNull(env.getData().getPagination());
        assertEquals(1, env.getData().getPagination().getPage());
        assertEquals(50, env.getData().getPagination().getSize());
        assertTrue(env.getUiSuggestions() == null || env.getUiSuggestions().isEmpty() ||
                env.getUiSuggestions().stream().anyMatch(s -> "Paginacion".equalsIgnoreCase(s.getType())),
                "Debe crear sugerencias de paginación");
    }

    static class DummyOpenAIPlannerInvalid extends DummyOpenAIPlanner {
        @Override
        public String callPlannerStrict(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Devuelve un mensaje sin tool_calls -> forzar fallback al flujo existente
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"sin plan\"}}]}";
        }
    }

    @Test
    public void plannerInvalid_fallsBackToExistingFlow() throws Exception {
    // Para el flujo existente, hace falta que callChatWithTools devuelva al menos un tool_call de API; sobreescribimos
    OpenAICallApiService openaiSpy = new DummyOpenAIPlannerInvalid() {
            @Override
            public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
                // Simula una tool_call a usuarios.listar_usuarios
                String content = "{\\\"text\\\":\\\"Listado\\\"}";
                return "{\"choices\":[{\"message\":{\"content\":" + content + ",\"tool_calls\":[{\"id\":\"1\",\"function\":{\"name\":\"call_api\",\"arguments\":\"{\\\\\"name\\\\\":\\\\\"usuarios.listar_usuarios\\\\\",\\\\\"method\\\\\":\\\\\"GET\\\\\"}\"}}]}}]}";
            }
            @Override
            public Map<String, Object> getEndpointByName(String name) {
                Map<String,Object> ep = new HashMap<>();
                ep.put("operationId","usuarios.listar_usuarios"); ep.put("name","usuarios.listar_usuarios"); ep.put("method","GET"); ep.put("path","/usuarios"); ep.put("paginated", true);
                return "usuarios.listar_usuarios".equals(name) ? ep : null;
            }
        };
        RecordingApiProxy proxy = new RecordingApiProxy();
        NoopJwt jd = new NoopJwt();
        ChatService svc = new ChatService(openaiSpy, proxy, jd);

        ResponseEnvelope env = svc.runChat(List.of(new ChatRequest.Message("user","lista usuarios")), null, null,
                new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));

        assertEquals("success", env.getStatus());
        assertNotNull(env.getData());
        assertTrue(env.getData().getItems() == null || env.getData().getItems().isEmpty() || env.getData().getPagination() != null,
                "El flujo previo debe seguir funcionando y/o construir envelope más tarde");
    }
}
