package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SpecLoaderServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void resolveRef_resolves_component_schema() throws Exception {
        String src = "{\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"MyType\": { \"type\": \"object\", \"properties\": { \"a\": { \"type\": \"string\" } } }\n" +
                "    }\n" +
                "  },\n" +
                "  \"node\": { \"$ref\": \"#/components/schemas/MyType\" }\n" +
                "}";
        JsonNode root = mapper.readTree(src);
        JsonNode node = root.get("node");

        SpecLoaderService svc = new SpecLoaderService();
        Method m = SpecLoaderService.class.getDeclaredMethod("resolveRefIfNeeded", JsonNode.class, JsonNode.class);
        m.setAccessible(true);
        JsonNode resolved = (JsonNode) m.invoke(svc, node, root);

        assertNotNull(resolved, "resolved should not be null");
        assertTrue(resolved.has("properties"), "resolved should contain properties");
        assertTrue(resolved.get("properties").has("a"));
    }

    @Test
    public void resolveRef_resolves_array_items_ref() throws Exception {
        String src = "{\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"Item\": { \"type\": \"object\", \"properties\": { \"id\": { \"type\": \"integer\" } } }\n" +
                "    }\n" +
                "  },\n" +
                "  \"node\": { \"type\": \"array\", \"items\": { \"$ref\": \"#/components/schemas/Item\" } }\n" +
                "}";
        JsonNode root = mapper.readTree(src);
        JsonNode node = root.get("node");

        SpecLoaderService svc = new SpecLoaderService();
        Method m = SpecLoaderService.class.getDeclaredMethod("resolveRefIfNeeded", JsonNode.class, JsonNode.class);
        m.setAccessible(true);
        // Resolve schema (should return same array node for top-level), then resolve items
        JsonNode resolvedSchema = (JsonNode) m.invoke(svc, node, root);
        assertNotNull(resolvedSchema);
        JsonNode items = (JsonNode) m.invoke(svc, resolvedSchema.get("items"), root);
        assertNotNull(items);
        assertTrue(items.has("properties"));
        assertTrue(items.get("properties").has("id"));
    }
}
