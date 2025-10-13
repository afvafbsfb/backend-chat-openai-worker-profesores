package com.workers.profesores.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAICallApiServiceSpecAdapterTest {

    @Test
    public void specLoaderInjectionShouldPopulateWhitelist() throws Exception {
        // Use a small stub SpecLoaderService that returns a deterministic whitelist
        SpecLoaderService specLoader = new SpecLoaderService() {
            @Override
            public List<Map<String, Object>> getWhitelist() {
                Map<String,Object> ep = new java.util.HashMap<>();
                ep.put("operationId", "academias.listar_academias");
                ep.put("name", "academias.listar_academias");
                ep.put("method", "GET");
                ep.put("path", "/academias");
                ep.put("description", "Listado de academias");
                return List.of(ep);
            }
        };

        // Create service under test and inject stub
        OpenAICallApiService svc = new OpenAICallApiService();
        svc.setSpecLoaderService(specLoader);

        // After injection, the endpoints should be available through the public API
        List<Map<String,Object>> wl = svc.getEndpoints();
        assertNotNull(wl);
        assertEquals(1, wl.size(), "Should have exactly one endpoint from the stub spec");
        // Ensure the entry contains operationId
        assertEquals("academias.listar_academias", wl.get(0).get("operationId"));
    }
}
