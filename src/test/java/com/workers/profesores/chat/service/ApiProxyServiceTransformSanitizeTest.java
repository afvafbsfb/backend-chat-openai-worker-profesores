package com.workers.profesores.chat.service;

import com.workers.profesores.chat.auth.UserClaims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ApiProxyServiceTransformSanitizeTest {

    @Test
    public void transformIfNeeded_invokesOpenAiEndpointReplacement() throws Exception {
        ApiProxyService proxy = new ApiProxyService();
        // Mock collaborators
        var jwtVerifier = mock(com.workers.profesores.chat.auth.JwtVerifier.class);
        var authorizationService = mock(com.workers.profesores.chat.auth.AuthorizationService.class);
        var openai = mock(OpenAICallApiService.class);

        // Inject mocks via reflection
        java.lang.reflect.Field f1 = ApiProxyService.class.getDeclaredField("jwtVerifier");
        f1.setAccessible(true);
        f1.set(proxy, jwtVerifier);
        java.lang.reflect.Field f2 = ApiProxyService.class.getDeclaredField("authorizationService");
        f2.setAccessible(true);
        f2.set(proxy, authorizationService);
        java.lang.reflect.Field f3 = ApiProxyService.class.getDeclaredField("openAICallApiService");
        f3.setAccessible(true);
        f3.set(proxy, openai);

        // Prepare endpoint and claims
        Map<String,Object> endpoint = Map.of("operationId", "op1", "name", "op1", "method", "GET", "path", "/test", "query", List.of());
        String token = "Bearer tkn";

        // jwtVerifier will return UserClaims
    when(jwtVerifier.verify(anyString())).thenReturn(new com.workers.profesores.chat.auth.UserClaims(5L, List.of("Admin_academia"), 1, null, "chat-backend", 2));

        // authorizationService.transformIfNeeded returns a transform pointing to new endpoint name
        when(authorizationService.transformIfNeeded(any(UserClaims.class), eq(endpoint), any(), any()))
                .thenReturn(Map.of("transform_to", "op2"));

        // openAICallApiService.getEndpointByName will be called for op2
        when(openai.getEndpointByName("op2")).thenReturn(Map.of("operationId", "op2", "name", "op2", "method", "GET", "path", "/op2"));

        // Execute - should not throw
        String res = proxy.executeSpecCall(endpoint, "GET", null, null, null, token);
        assertNotNull(res);

        // Verify interactions
        verify(jwtVerifier, times(1)).verify(anyString());
        verify(authorizationService, times(1)).transformIfNeeded(any(UserClaims.class), eq(endpoint), any(), any());
        verify(openai, times(1)).getEndpointByName("op2");
    }

    @Test
    public void sanitizeParams_called_when_no_transform() throws Exception {
        ApiProxyService proxy = new ApiProxyService();
        var jwtVerifier = mock(com.workers.profesores.chat.auth.JwtVerifier.class);
        var authorizationService = mock(com.workers.profesores.chat.auth.AuthorizationService.class);

        java.lang.reflect.Field f1 = ApiProxyService.class.getDeclaredField("jwtVerifier");
        f1.setAccessible(true);
        f1.set(proxy, jwtVerifier);
        java.lang.reflect.Field f2 = ApiProxyService.class.getDeclaredField("authorizationService");
        f2.setAccessible(true);
        f2.set(proxy, authorizationService);

        Map<String,Object> endpoint = Map.of("operationId", "op1", "name", "op1", "method", "GET", "path", "/test", "query", List.of());
        String token = "Bearer tkn";

    when(jwtVerifier.verify(anyString())).thenReturn(new com.workers.profesores.chat.auth.UserClaims(5L, List.of("Admin_academia"), 1, null, "chat-backend", 2));
        when(authorizationService.transformIfNeeded(any(UserClaims.class), eq(endpoint), any(), any())).thenReturn(null);
    java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> sanitized = new java.util.HashMap<>();
    sanitized.put("pathParams", com.fasterxml.jackson.databind.node.NullNode.instance);
    sanitized.put("query", com.fasterxml.jackson.databind.node.NullNode.instance);
    when(authorizationService.sanitizeParams(any(UserClaims.class), eq(endpoint), any(), any())).thenReturn(sanitized);

        // Call - will attempt to perform real HTTP exchange and likely fail; we only care about interactions before that
        try {
            proxy.executeSpecCall(endpoint, "GET", null, null, null, token);
        } catch (Exception ignored) { }

        verify(jwtVerifier, times(1)).verify(anyString());
        verify(authorizationService, times(1)).sanitizeParams(any(UserClaims.class), eq(endpoint), any(), any());
    }
}
