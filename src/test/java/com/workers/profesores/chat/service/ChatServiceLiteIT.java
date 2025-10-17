package com.workers.profesores.chat.service;

// Removed unused imports
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

// Removed unused import
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style test (lightweight) for the second-turn lite flow.
 * We stub OpenAICallApiService to return deterministic messages:
 *  - First call returns a tool_call for a paginated GET of usuarios
 *  - ApiProxyService returns a JSON with items/page/size
 *  - Second call (with response_format) returns a compact JSON {text,suggestions,summary_fields}
 */
public class ChatServiceLiteIT {
    // no-op

    @Test
    public void testSecondTurnLiteUsuarios() throws Exception {
        // Arrange services
        OpenAICallApiService openai = Mockito.spy(new OpenAICallApiService());
        ApiProxyService apiProxy = Mockito.mock(ApiProxyService.class);
        com.workers.profesores.chat.auth.JwtDelegationService jwt = Mockito.mock(com.workers.profesores.chat.auth.JwtDelegationService.class);
        ChatService chat = new ChatService(openai, apiProxy, jwt);
        // Enable lite, disable fastpath to validate branch
        java.lang.reflect.Field fLite = ChatService.class.getDeclaredField("secondTurnLiteEnabled");
        fLite.setAccessible(true);
        fLite.set(chat, true);
        java.lang.reflect.Field fFast = ChatService.class.getDeclaredField("fastpathEnabled");
        fFast.setAccessible(true);
        fFast.set(chat, false);
        // Delegated token
        Mockito.when(jwt.createDelegatedAuthorizationHeader(Mockito.any())).thenReturn("Bearer delegated-token");

        // Stub OpenAI first: minimal shape with tool_calls
        String first = "{\n  \"choices\": [ { \n    \"message\": { \n      \"tool_calls\": [ { \n        \"id\": \"call_1\", \n        \"function\": { \n          \"name\": \"call_api\", \n          \"arguments\": \"{\\\"name\\\":\\\"usuarios.listar\\\",\\\"method\\\":\\\"GET\\\"}\" \n        } \n      } ] \n    } } ] \n}";
        Mockito.doReturn(first).when(openai).callChatWithTools(Mockito.anyList(), Mockito.any(), Mockito.any());

        // Stub api proxy: usuarios with items + pagination
        String apiResult = "{\n  \"items\": [ {\"id\":1,\"nombre\":\"Uno\",\"email\":\"uno@ejemplo.com\"}, {\"id\":2,\"nombre\":\"Dos\"} ],\n  \"page\": 1,\n  \"size\": 20,\n  \"has_more\": true,\n  \"next_page\": 2\n}";
        Mockito.when(apiProxy.executeSpecCall(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any())).thenReturn(apiResult);

        // Stub OpenAI second (with extras): return compact JSON content
        String second = "{\n  \"choices\": [ { \n    \"message\": { \n      \"content\": \"{\\\"text\\\":\\\"Listado de usuarios (2).\\\",\\\"suggestions\\\":[\\\"Siguiente página\\\",\\\"Exportar a CSV\\\"],\\\"summary_fields\\\":[\\\"nombre\\\",\\\"email\\\"]}\" \n    } } ] \n}";
        Mockito.doReturn(second).when(openai).callChatWithToolsWithExtras(Mockito.anyList(), Mockito.anyMap(), Mockito.any(), Mockito.any());

        // Build request
        List<ChatRequest.Message> msgs = List.of(new ChatRequest.Message("user","Dame los usuarios"));
        com.workers.profesores.chat.util.RequestFlowXmlLogger xml = null;
    com.workers.profesores.chat.auth.UserClaims claims = new com.workers.profesores.chat.auth.UserClaims(1L, List.of("Admin_plataforma"), 1, null);
        ResponseEnvelope out = chat.runChat(msgs, xml, "Bearer original", claims);

        // Assert envelope fusion
        assertEquals("success", out.getStatus());
        assertNotNull(out.getData());
        assertEquals("usuarios", out.getData().getType());
        assertEquals(2, out.getData().getItems().size());
        assertNotNull(out.getData().getPagination());
        assertEquals(1, out.getData().getPagination().getPage());
        assertEquals(20, out.getData().getPagination().getSize());
        assertEquals("Listado de usuarios (2).", out.getMessage());
        assertTrue(out.getSuggestions()!=null && out.getSuggestions().size()>=1);
        assertNotNull(out.getData().getSummaryFields());
        assertTrue(out.getData().getSummaryFields().contains("nombre"));
    }
}
