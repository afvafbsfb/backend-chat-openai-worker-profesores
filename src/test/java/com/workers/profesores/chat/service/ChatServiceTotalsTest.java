package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ChatServiceTotalsTest {

    private OpenAICallApiService mockOpenAI;
    private ApiProxyService mockApiProxy;
    private ChatService chatService;
    private ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockOpenAI = mock(OpenAICallApiService.class);
        mockApiProxy = mock(ApiProxyService.class);
        chatService = new ChatService(mockOpenAI, mockApiProxy);

        // Provide a whitelist where getAlumnos is paginated and getTurnosLibres is not
        List<Map<String, Object>> whitelist = new ArrayList<>();
        whitelist.add(Map.of(
                "name", "getAlumnos",
                "method", "GET",
                "path", "/vlodeiro/secretaria/alumnos",
                "paginated", true,
                "query", List.of("page", "size")
        ));
        whitelist.add(Map.of(
                "name", "getTurnosLibres",
                "method", "GET",
                "path", "/vlodeiro/secretaria/turnos_libres",
                "paginated", false
        ));

        ReflectionTestUtils.setField(mockOpenAI, "whitelist", whitelist);
        // Also ensure renderWhitelistTable won't fail
        when(mockOpenAI.renderWhitelistTable()).thenReturn("[whitelist table]");
    }

    @Test
    void paginatedEndpoint_returnsOnlyTotalNumber() throws Exception {
        // Simula que OpenAI devuelve un tool_call para getAlumnos
        String openaiFirst = "{ \"choices\": [ { \"message\": { \"role\": \"assistant\", \"content\": \"\", \"tool_calls\": [ { \"id\": \"1\", \"function\": { \"name\": \"call_api\", \"arguments\": \"{\\\"name\\\":\\\"getAlumnos\\\",\\\"method\\\":\\\"GET\\\",\\\"query\\\":{\\\"page\\\":0,\\\"size\\\":1}}\" } } ] } } ] }";
        when(mockOpenAI.callChatWithTools(anyList(), any())).thenReturn(openaiFirst).thenReturn("{ \"choices\": [ { \"message\": { \"role\": \"assistant\", \"content\": \"123\" } } ] }");

        // Simula que ApiProxy devuelve una respuesta paginada con totalElements
        String apiResponse = "{ \"page\":0, \"size\":1, \"totalElements\": 123, \"items\": [ { \"id\":1 } ] }";
        when(mockApiProxy.executeWhitelistedCall(anyMap(), anyString(), any(), any(), any())).thenReturn(apiResponse);

        // Construye mensaje de usuario
        List<ChatRequest.Message> incoming = List.of(new ChatRequest.Message("user", "¿Cuántos alumnos hay?"));

        String result = chatService.runChat(incoming, null);

        // Debe responder SOLO con el número
        assertEquals("123", result.trim());
    }

    @Test
    void nonPaginatedEndpoint_returnsArrayLengthAsTotal() throws Exception {
        // Simula que OpenAI devuelve un tool_call para getTurnosLibres
        String openaiFirst = "{ \"choices\": [ { \"message\": { \"role\": \"assistant\", \"content\": \"\", \"tool_calls\": [ { \"id\": \"2\", \"function\": { \"name\": \"call_api\", \"arguments\": \"{\\\"name\\\":\\\"getTurnosLibres\\\",\\\"method\\\":\\\"GET\\\"}\" } } ] } } ] }";
        when(mockOpenAI.callChatWithTools(anyList(), any())).thenReturn(openaiFirst).thenReturn("{ \"choices\": [ { \"message\": { \"role\": \"assistant\", \"content\": \"2\" } } ] }");

        // ApiProxy devuelve un array JSON con dos elementos
        String apiResponse = "[ { \"id\": 1 }, { \"id\": 2 } ]";
        when(mockApiProxy.executeWhitelistedCall(anyMap(), anyString(), any(), any(), any())).thenReturn(apiResponse);

        List<ChatRequest.Message> incoming = List.of(new ChatRequest.Message("user", "¿Cuántos turnos existen?"));

        String result = chatService.runChat(incoming, null);

        // Debe responder solo con la longitud del array: 2
        assertEquals("2", result.trim());
    }
}
