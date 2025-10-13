package com.workers.profesores.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationServiceImplTest {
    private final AuthorizationServiceImpl svc = new AuthorizationServiceImpl();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    public void checkAllowed_allows_when_role_present_in_xpermissions() {
    UserClaims claims = new UserClaims(1L, List.of("Admin_academia"), 10, null, null);
        Map<String,Object> endpoint = Map.of("x-permissions", Map.of("allowed_roles", List.of("Admin_academia","Admin_plataforma")));
        assertDoesNotThrow(() -> svc.checkAllowed(claims, endpoint, "GET", null, null));
    }

    @Test
    public void checkAllowed_denies_when_role_not_in_xpermissions() {
    UserClaims claims = new UserClaims(2L, List.of("Profesor_academia"), 5, null, null);
        Map<String,Object> endpoint = Map.of("x-permissions", Map.of("allowed_roles", List.of("Admin_academia","Admin_plataforma")));
        assertThrows(RuntimeException.class, () -> svc.checkAllowed(claims, endpoint, "GET", null, null));
    }

    @Test
    public void sanitizeParams_applies_enforced_filters_current_user_academia_id() throws Exception {
    UserClaims claims = new UserClaims(3L, List.of("Admin_academia"), 77, null, null);
        Map<String,Object> endpoint = Map.of("x-permissions", Map.of("enforced_filters", Map.of("academia_id", "current_user.academia_id")));
        JsonNode path = om.createObjectNode();
        JsonNode query = om.createObjectNode();
        java.util.Map<String, JsonNode> out = svc.sanitizeParams(claims, endpoint, path, query);
        assertNotNull(out);
        JsonNode q = out.get("query");
        assertNotNull(q);
        assertTrue(q.has("academia_id"));
        assertEquals(77, q.get("academia_id").asInt());
    }

    @Test
    public void transformIfNeeded_transforms_listar_for_admin_academia() {
    UserClaims claims = new UserClaims(4L, List.of("Admin_academia"), 99, null, null);
        Map<String,Object> endpoint = Map.of("operationId", "usuarios.listar_usuarios");
        Map<String,Object> res = svc.transformIfNeeded(claims, endpoint, null, null);
        assertNotNull(res);
        assertTrue(res.containsKey("transform_to"));
        assertTrue(res.containsKey("pathParams"));
        Object pathParams = res.get("pathParams");
        assertNotNull(pathParams);
        // pathParams is an ObjectNode, but we put a JsonNode; ensure it contains id=claims.academiaId when serialized
        // convert to JsonNode for assertion
        try {
            JsonNode pn = (JsonNode) pathParams;
            assertEquals(99, pn.get("id").asInt());
        } catch (ClassCastException ex) {
            fail("pathParams is not a JsonNode as expected: " + pathParams);
        }
    }
}
