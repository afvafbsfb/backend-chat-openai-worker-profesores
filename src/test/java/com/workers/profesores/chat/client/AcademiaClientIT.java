package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AcademiaClientIT {
    @Autowired
    private AcademiaClient client;

    @Test
    void testListAlumnos_integration() {
        ReflectionTestUtils.setField(client, "apiKey", "dummy-key");
        ReflectionTestUtils.setField(client, "apiUrl", "http://localhost:9999/fake");
        try {
            JsonNode result = client.listAlumnos(1, 10, "");
            assertNull(result);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Connection refused") || e.getMessage().contains("I/O error"));
        }
    }
}
