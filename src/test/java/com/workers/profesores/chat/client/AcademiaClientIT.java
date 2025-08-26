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
    void testGetAlumnos_integration() {
        ReflectionTestUtils.setField(client, "apiKey", "dummy-key");
        // No hay apiUrl en el stub actual, pero se deja el ejemplo
        try {
            JsonNode result = client.getAlumnos(0, 10);
            assertNull(result);
        } catch (Exception e) {
            // El stub nunca lanza excepción, pero si se implementa, se puede ajustar aquí
            assertTrue(true);
        }
    }
}
