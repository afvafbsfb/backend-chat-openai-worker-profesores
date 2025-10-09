package com.workers.profesores.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAICallApiServiceSpecAdapterTest {

    @Test
    public void specLoaderInjectionShouldPopulateWhitelist() throws Exception {
        // Create a SpecLoaderService that will read the served-openapi.json from test resources
        SpecLoaderService specLoader = new SpecLoaderService();
        // Create service under test
        OpenAICallApiService svc = new OpenAICallApiService();
        // Inject specLoader
        svc.setSpecLoaderService(specLoader);
    // After injection, the endpoints should be available through the public API
    List<Map<String,Object>> wl = svc.getEndpoints();
    assertNotNull(wl);
    assertTrue(wl.size() > 0, "Endpoints loaded from spec should contain entries");
    // Ensure at least one entry contains operationId
    boolean anyHasOp = wl.stream().anyMatch(m -> m.containsKey("operationId") && m.get("operationId") != null);
    assertTrue(anyHasOp, "At least one spec entry should have operationId");
    }
}
