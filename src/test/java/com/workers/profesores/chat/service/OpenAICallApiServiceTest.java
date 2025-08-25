package com.workers.profesores.chat.service;

import org.springframework.web.client.RestTemplate;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAICallApiServiceTest {

    @Test
    void testCallChatWithTools_mocksRestTemplate() throws Exception {
        RestTemplate mockRestTemplate = Mockito.mock(RestTemplate.class);
        String fakeResponse = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\"}";
        Mockito.when(mockRestTemplate.postForEntity(
                Mockito.anyString(),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(ResponseEntity.ok(fakeResponse));
        OpenAICallApiService spyService = Mockito.spy(service);
        Mockito.doReturn(mockRestTemplate).when(spyService).createRestTemplate();
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", "Hola")
        );
        ReflectionTestUtils.setField(spyService, "openaiApiKey", "test-key");
        String result = spyService.callChatWithTools(messages);
        assertTrue(result.contains("chatcmpl-123"));
    }

    @Test
    void testRenderWhitelistTable_normal() {
        String table = service.renderWhitelistTable();
        assertTrue(table.contains("Whitelist de endpoints permitidos"));
        assertTrue(table.contains("getUser"));
        assertTrue(table.contains("createUser"));
    }

    @Test
    void testRenderWhitelistTable_empty() {
        ReflectionTestUtils.setField(service, "whitelist", Collections.emptyList());
        String table = service.renderWhitelistTable();
        assertTrue(table.contains("No hay endpoints"));
    }

    @Test
    void testRenderWhitelistTableMarkdown_emptyList() {
        String markdown = service.renderWhitelistTableMarkdown(Collections.emptyList());
        assertTrue(markdown.contains("No hay endpoints"));
    }

    @Test
    void testRenderWhitelistTableMarkdown_nullList() {
        String markdown = service.renderWhitelistTableMarkdown(null);
        assertTrue(markdown.contains("No hay endpoints"));
    }

    @Test
    void testRenderWhitelistNarrativeWithDescriptions_emptyList() {
        String narrative = service.renderWhitelistNarrativeWithDescriptions(Collections.emptyList());
        assertTrue(narrative.contains("No hay endpoints"));
    }

    @Test
    void testRenderWhitelistNarrativeWithDescriptions_nullList() {
        String narrative = service.renderWhitelistNarrativeWithDescriptions(null);
        assertTrue(narrative.contains("No hay endpoints"));
    }

    @Test
    void testRenderWhitelistSectionCombined_flagsFalse() {
        ReflectionTestUtils.setField(service, "whitelistRenderTable", false);
        ReflectionTestUtils.setField(service, "whitelistRenderNarrative", false);
        String combined = service.renderWhitelistSectionCombined();
        assertEquals("", combined);
    }
    private OpenAICallApiService service;

    @BeforeEach
    void setUp() {
        service = new OpenAICallApiService();
        // Inyectar una whitelist de prueba para tests unitarios
        List<Map<String, Object>> whitelist = List.of(
            Map.of(
                "name", "getUser",
                "method", "GET",
                "path", "/users/{id}",
                "description", "Obtiene un usuario por ID",
                "pathParams", List.of("id"),
                "query", List.of(),
                "body", List.of()
            ),
            Map.of(
                "name", "createUser",
                "method", "POST",
                "path", "/users",
                "description", "Crea un nuevo usuario",
                "pathParams", List.of(),
                "query", List.of(),
                "body", List.of("name", "email")
            )
        );
        ReflectionTestUtils.setField(service, "whitelist", whitelist);
        ReflectionTestUtils.setField(service, "whitelistRenderTable", true);
        ReflectionTestUtils.setField(service, "whitelistRenderNarrative", true);
        ReflectionTestUtils.setField(service, "whitelistMaxEndpoints", 0);
    }

    @Test
    void testRenderWhitelistTableMarkdown() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ReflectionTestUtils.getField(service, "whitelist");
        String markdown = service.renderWhitelistTableMarkdown(list);
        assertTrue(markdown.contains("| name | method | path | pathParams | query | body | description |"));
        assertTrue(markdown.contains("getUser"));
        assertTrue(markdown.contains("createUser"));
    }

    @Test
    void testRenderWhitelistNarrativeWithDescriptions() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ReflectionTestUtils.getField(service, "whitelist");
        String narrative = service.renderWhitelistNarrativeWithDescriptions(list);
        assertTrue(narrative.contains("Obtiene un usuario por ID"));
        assertTrue(narrative.contains("Crea un nuevo usuario"));
    }

    @Test
    void testRenderWhitelistSectionCombined_TableAndNarrative() {
        String combined = service.renderWhitelistSectionCombined();
        assertTrue(combined.contains("Endpoints permitidos (tabla)"));
        assertTrue(combined.contains("Endpoints permitidos (descripción)"));
    }

    @Test
    void testWhitelistMaxEndpointsLimit() {
        ReflectionTestUtils.setField(service, "whitelistMaxEndpoints", 1);
        String combined = service.renderWhitelistSectionCombined();
        assertTrue(combined.contains("getUser"));
        assertFalse(combined.contains("createUser"));
    }

    @Test
    void testGetEndpointByName() {
        Map<String, Object> ep = service.getEndpointByName("getUser");
        assertNotNull(ep);
        assertEquals("GET", ep.get("method"));
        assertNull(service.getEndpointByName("noExiste"));
    }
    @Test
    void testConstructor_handlesYamlNotFoundAndMalformed() {
        // --- Caso 1: Archivo inexistente (simulado con InputStream nulo que lanza FileNotFoundException) ---
        OpenAICallApiService serviceNotFound = new OpenAICallApiService(new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.FileNotFoundException("not found"); }
        });
        assertEquals(List.of(), ReflectionTestUtils.getField(serviceNotFound, "whitelist"));

        // --- Caso 2: YAML mal formado ---
        byte[] invalidYaml = ":::::esto no es yaml::::".getBytes();
        OpenAICallApiService serviceMalformed = new OpenAICallApiService(new java.io.ByteArrayInputStream(invalidYaml));
        assertEquals(List.of(), ReflectionTestUtils.getField(serviceMalformed, "whitelist"));
    }
}
