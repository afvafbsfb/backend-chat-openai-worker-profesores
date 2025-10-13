package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.JwtDelegationService;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChatServiceStrictReuseMockitoTest {

    static class DummyOpenAI extends OpenAICallApiService {
        @Override
        public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
            // Return two tool_calls to force two proxied calls
            return "{\"choices\":[{\"message\":{\"content\":\"Respuesta\",\"tool_calls\": [" +
                    "{\"id\":\"1\",\"function\":{\"name\":\"call_api\",\"arguments\": \"{\\\"name\\\":\\\"academias.listar_academias\\\",\\\"method\\\":\\\"GET\\\"}\"}}," +
                    "{\"id\":\"2\",\"function\":{\"name\":\"call_api\",\"arguments\": \"{\\\"name\\\":\\\"academias.listar_academias\\\",\\\"method\\\":\\\"GET\\\"}\"}}]}}]}";
        }

        @Override
        public Map<String, Object> getEndpointByName(String name) {
            return Map.of("operationId", name, "name", name, "method", "GET", "path", "/academias");
        }
    }

    @Test
    public void jwtTokenIsReusedAcrossProxyCalls() throws Exception {
        // Arrange
        OpenAICallApiService openai = new DummyOpenAI();
        ApiProxyService apiProxy = mock(ApiProxyService.class);
        when(apiProxy.executeSpecCall(anyMap(), anyString(), any(), any(), any(), anyString())).thenReturn("{\"ok\":true}");

        JwtDelegationService jwtDelegation = mock(JwtDelegationService.class);
        when(jwtDelegation.createDelegatedAuthorizationHeader(any(UserClaims.class))).thenReturn("Bearer fixed-mock-token");

        ChatService svc = new ChatService(openai, apiProxy, jwtDelegation);

        // Act
    svc.runChat(List.of(new ChatRequest.Message("user", "dame academias")), null, null, new UserClaims(1L, List.of("Admin_academia"), 1, null, null, 1));

        // Assert: delegation created exactly once
        verify(jwtDelegation, times(1)).createDelegatedAuthorizationHeader(any(UserClaims.class));

        // Assert: apiProxy.executeSpecCall called at least twice and with same authorization for all captured calls
        ArgumentCaptor<String> authCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiProxy, atLeast(2)).executeSpecCall(anyMap(), anyString(), any(), any(), any(), authCaptor.capture());
        List<String> usedAuths = authCaptor.getAllValues();
        // Filter out nulls (calls before delegated token creation, e.g. profile prefetch)
        List<String> nonNull = usedAuths.stream().filter(Objects::nonNull).toList();
        assertTrue(nonNull.size() >= 2, "Expected at least two proxied calls using a delegated token");
        for (String a : nonNull) {
            assertEquals("Bearer fixed-mock-token", a);
        }
    }
}
