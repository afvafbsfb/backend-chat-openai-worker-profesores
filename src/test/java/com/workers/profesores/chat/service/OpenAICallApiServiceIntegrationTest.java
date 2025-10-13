package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAICallApiServiceIntegrationTest {
    private ObjectMapper mapper = new ObjectMapper();

    static class TestableService extends OpenAICallApiService {
        private final RestTemplate rt;
        public TestableService(RestTemplate rt) { this.rt = rt; }
        @Override
        protected RestTemplate createRestTemplate() { return rt; }
    }

    private RestTemplate rt;
    private MockRestServiceServer server;
    private TestableService service;

    @BeforeEach
    void setUp() {
        rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        service = new TestableService(rt);
        // stub ApiProxyService
        service.setApiProxyService(new ApiProxyService() {
            @Override
            public String executeSpecCall(Map<String, Object> endpoint, String method, com.fasterxml.jackson.databind.JsonNode pathParams, com.fasterxml.jackson.databind.JsonNode query, com.fasterxml.jackson.databind.JsonNode body, String authorization) {
                return "{\"result\":\"ok\",\"from_proxy\":\"/academias\"}";
            }
        });
        try {
            java.lang.reflect.Field fModel = OpenAICallApiService.class.getDeclaredField("openaiApiModel");
            fModel.setAccessible(true);
            fModel.set(service, "test-model");
            java.lang.reflect.Field fKey = OpenAICallApiService.class.getDeclaredField("openaiApiKey");
            fKey.setAccessible(true);
            fKey.set(service, "test-key");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void openAiToolCallingFlow_shouldReturnFinalContent() throws Exception {
        // Prepare two OpenAI responses
        Map<String,Object> first = new HashMap<>();
        first.put("message", Map.of("role","assistant","content","Invoking tool"));
        Map<String,Object> toolCall = new HashMap<>();
        toolCall.put("name","call_api");
        toolCall.put("arguments", Map.of("name","academias.listar_academias","method","GET","pathParams",Map.of(),"query",Map.of(),"body",Map.of()));
        first.put("tool_calls", List.of(toolCall));

        Map<String,Object> second = new HashMap<>();
        second.put("message", Map.of("role","assistant","content","Here are the academias"));
        second.put("tool_calls", List.of());

        String firstBody = mapper.writeValueAsString(first);
        String secondBody = mapper.writeValueAsString(second);

        // Expect two POST calls to OpenAI completions URL
    java.lang.reflect.Method urlMethod = OpenAICallApiService.class.getDeclaredMethod("composeCompletionsUrl");
    urlMethod.setAccessible(true);
    String url = (String) urlMethod.invoke(service);
        server.expect(MockRestRequestMatchers.requestTo(url)).andRespond(MockRestResponseCreators.withSuccess(firstBody, MediaType.APPLICATION_JSON));
        server.expect(MockRestRequestMatchers.requestTo(url)).andRespond(MockRestResponseCreators.withSuccess(secondBody, MediaType.APPLICATION_JSON));

        // Prepare initial messages
        List<Map<String,Object>> messages = new ArrayList<>();
        messages.add(Map.of("role","user","content","Dame academias"));

        // Ensure whitelist contains the operationId used
        Map<String,Object> ep = new HashMap<>(); ep.put("operationId","academias.listar_academias"); ep.put("path","/academias"); ep.put("method","GET");
        Field f = OpenAICallApiService.class.getDeclaredField("whitelist"); f.setAccessible(true); f.set(service, List.of(ep));

        String result = service.callChatWithTools(messages, null, null);

        assertNotNull(result);
        assertTrue(result.contains("Here are the academias"));

        server.verify();
    }
}
