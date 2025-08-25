package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OpenAIClientIT {
    @Autowired
    private OpenAIClient client;

    @Test
    void testCreateResponse_integration() {
        // Configura apiKey y apiUrl a valores dummy para evitar llamadas reales
        ReflectionTestUtils.setField(client, "apiKey", "dummy-key");
        ReflectionTestUtils.setField(client, "apiUrl", "http://localhost:9999/fake");
        List<String> messages = List.of("msg1");
        List<String> tools = List.of("tool1");
        // Espera excepción o null porque el endpoint no existe
        try {
            JsonNode result = client.createResponse(messages, tools);
            assertNull(result);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Connection refused") || e.getMessage().contains("I/O error"));
        }
    }
}
