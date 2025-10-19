package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PaginationDefaultsTest {

    static class RecordingApiProxy extends ApiProxyService {
        Map<String,Object> lastEndpoint;
        String lastMethod;
        JsonNode lastPathParams;
        JsonNode lastQuery;
        JsonNode lastBody;
        String lastAuth;
        @Override
        public String executeSpecCall(Map<String, Object> endpoint, String method, JsonNode pathParams, JsonNode query, JsonNode body, String authorization) {
            this.lastEndpoint = endpoint;
            this.lastMethod = method;
            this.lastPathParams = pathParams;
            this.lastQuery = query;
            this.lastBody = body;
            this.lastAuth = authorization;
            return "{\"ok\":true}";
        }
    }

    static class DummyOpenAI_NoNetwork extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Simula una respuesta con un único tool_call a un endpoint paginado
            return "{\"choices\":[{\"message\":{\"content\":\"respuesta\",\"tool_calls\":[{\"id\":\"1\",\"function\":{\"name\":\"call_api\",\"arguments\":\"{\\\"name\\\":\\\"usuarios.listar_usuarios\\\",\\\"method\\\":\\\"GET\\\"}\"}}]}}]}";
        }
    }

    @Test
    public void defaultSizeIs50AndCappedAt50() throws Exception {
        RecordingApiProxy proxy = new RecordingApiProxy();
        DummyOpenAI_NoNetwork openai = new DummyOpenAI_NoNetwork();
        // Inyectar whitelist con endpoint paginado
        Map<String,Object> ep = new HashMap<>();
        ep.put("operationId","usuarios.listar_usuarios");
        ep.put("name","usuarios.listar_usuarios");
        ep.put("method","GET");
        ep.put("path","/usuarios");
        ep.put("paginated", true);
        java.lang.reflect.Field f = OpenAICallApiService.class.getDeclaredField("whitelist");
        f.setAccessible(true);
        f.set(openai, List.of(ep));
        openai.setApiProxyService(proxy);

    // Caso A: modelo no especifica size -> debe ser 20
    // Construimos una tool_call sin query.size y ejecutamos processToolCalls para que llame al proxy
    Map<String,Object> argsA = new HashMap<>();
    argsA.put("name", "usuarios.listar_usuarios");
    argsA.put("method", "GET");
    Map<String,Object> toolA = new HashMap<>();
    toolA.put("id", "1");
    toolA.put("name", "call_api");
    toolA.put("arguments", argsA);
    Map<String,Object> responseA = new HashMap<>();
    responseA.put("tool_calls", List.of(toolA));
    java.lang.reflect.Method mA = OpenAICallApiService.class.getDeclaredMethod("processToolCalls", Map.class, List.class, com.workers.profesores.chat.util.RequestFlowXmlLogger.class, String.class);
    mA.setAccessible(true);
    boolean executedA = (boolean) mA.invoke(openai, responseA, new ArrayList<>(), null, null);
    assertTrue(executedA, "Debe ejecutarse la tool_call A");
    assertNotNull(proxy.lastQuery, "Debe capturarse la query en proxy");
        int sizeA = proxy.lastQuery.has("size") && proxy.lastQuery.get("size").canConvertToInt() ? proxy.lastQuery.get("size").asInt() : -1;
        assertEquals(50, sizeA, "size por defecto debe ser 50");
        int pageA = proxy.lastQuery.has("page") && proxy.lastQuery.get("page").canConvertToInt() ? proxy.lastQuery.get("page").asInt() : -1;
        assertEquals(1, pageA, "page por defecto debe ser 1");

        // Caso B: modelo pide size=200 -> debe capear a 50
        RecordingApiProxy proxyB = new RecordingApiProxy();
        openai.setApiProxyService(proxyB);
    // Forzar tool_call con query.size=200 construyendo el mapa directamente
    List<Map<String,Object>> msgs = new ArrayList<>();
    msgs.add(Map.of("role","system","content","sys"));
    msgs.add(Map.of("role","user","content","listar"));
    Map<String,Object> arguments = new HashMap<>();
    arguments.put("name","usuarios.listar_usuarios");
    arguments.put("method","GET");
    arguments.put("query", Map.of("size", 200));
    Map<String,Object> tool = new HashMap<>();
    tool.put("id","1");
    tool.put("name","call_api");
    tool.put("arguments", arguments);
    Map<String,Object> response = new HashMap<>();
    response.put("tool_calls", List.of(tool));
        // Invocar método privado processToolCalls via reflexión
        java.lang.reflect.Method m = OpenAICallApiService.class.getDeclaredMethod("processToolCalls", Map.class, List.class, com.workers.profesores.chat.util.RequestFlowXmlLogger.class, String.class);
        m.setAccessible(true);
        boolean anyExecuted = (boolean) m.invoke(openai, response, new ArrayList<>(msgs), null, null);
        assertTrue(anyExecuted, "Debe ejecutar al menos una tool_call");
        assertNotNull(proxyB.lastQuery);
        int sizeB = proxyB.lastQuery.has("size") && proxyB.lastQuery.get("size").canConvertToInt() ? proxyB.lastQuery.get("size").asInt() : -1;
        assertEquals(50, sizeB, "size debe estar capado a 50");
    }
}
