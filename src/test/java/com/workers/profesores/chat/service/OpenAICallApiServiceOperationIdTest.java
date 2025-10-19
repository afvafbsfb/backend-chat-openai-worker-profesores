package com.workers.profesores.chat.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class OpenAICallApiServiceOperationIdTest {

    @Test
    public void getEndpointByOperationIdShouldFind() throws Exception {
    // Use the test-friendly constructor that accepts an InputStream with a minimal YAML whitelist
    String yaml = "endpoints:\n  - name: academias.listar_academias\n    operationId: academias.listar_academias\n    path: /academias\n    method: GET\n";
    java.io.InputStream is = new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    OpenAICallApiService svc = new OpenAICallApiService(null, is);

    Map<String, Object> found = svc.getEndpointByName("academias.listar_academias");
        assertNotNull(found, "Should resolve endpoint by operationId");
        assertEquals("/academias", found.get("path"));
    }
}
