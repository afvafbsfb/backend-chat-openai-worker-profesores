package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

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

    @Test
    public void loadSpec_shouldLoadFromClasspath_whenNoUrl() throws Exception {
        // Arrange: create service with no URL configured
        SpecLoaderService svc = new SpecLoaderService();
        try {
            Field fUrl = SpecLoaderService.class.getDeclaredField("servedOpenapiUrl");
            fUrl.setAccessible(true);
            fUrl.set(svc, "");
        } catch (NoSuchFieldException ignore) { /* field injection may be absent in plain instantiation */ }

        // Act: invoke private loadSpec() via reflection
        Method m = SpecLoaderService.class.getDeclaredMethod("loadSpec");
        m.setAccessible(true);
        com.fasterxml.jackson.databind.JsonNode root = (com.fasterxml.jackson.databind.JsonNode) m.invoke(svc);

        // Assert: spec is loaded from classpath and has expected structure
        assertNotNull(root, "El spec debe cargarse desde classpath cuando no hay URL");
        assertTrue(root.has("openapi"), "El spec debe contener el campo 'openapi'");
        com.fasterxml.jackson.databind.JsonNode paths = root.get("paths");
        assertNotNull(paths, "Debe existir la sección 'paths'");
        assertTrue(paths.has("/academias"), "Debe existir el path /academias en el spec");

        // Comprobar que /academias GET tiene parámetros de paginación con default/min/max esperados
        com.fasterxml.jackson.databind.JsonNode getAcademias = paths.path("/academias").path("get");
        assertTrue(getAcademias.has("parameters"), "GET /academias debe definir parámetros");
        boolean foundSize = false;
        for (com.fasterxml.jackson.databind.JsonNode p : getAcademias.get("parameters")) {
            if (p.has("name") && "size".equals(p.get("name").asText())) {
                com.fasterxml.jackson.databind.JsonNode schema = p.get("schema");
                assertNotNull(schema, "El parámetro 'size' debe tener 'schema'");
                assertEquals(20, schema.path("default").asInt(), "size.default debe ser 20");
                assertEquals(1, schema.path("minimum").asInt(), "size.minimum debe ser 1");
                assertEquals(100, schema.path("maximum").asInt(), "size.maximum debe ser 100");
                foundSize = true;
            }
        }
        assertTrue(foundSize, "Debe existir el parámetro de query 'size' en GET /academias");
    }
}
