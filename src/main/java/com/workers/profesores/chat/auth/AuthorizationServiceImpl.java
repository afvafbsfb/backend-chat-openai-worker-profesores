package com.workers.profesores.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuthorizationServiceImpl implements AuthorizationService {
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void checkAllowed(UserClaims claims, Map<String, Object> endpoint, String method, JsonNode pathParams, JsonNode query) {
        if (claims == null) throw new RuntimeException("Forbidden: no claims provided");
        if (claims.isAdminPlataforma()) return; // todo permitido
        if (claims.isAdminAcademia()) {
            // Denegar operaciones de gestión de academias si endpoint contiene create/delete on academias
            String path = String.valueOf(endpoint.getOrDefault("path", ""));
            if (path.contains("/academias") && ("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                throw new RuntimeException("Forbidden: Admin_academia no puede crear/borrar academias");
            }
            // el resto permitido siempre que se limite a su academia; la sanitización se encargará de forzar academy_id
            return;
        }
        if (claims.isProfesorAcademia()) {
            // Por ahora, profesores no tienen permisos
            throw new RuntimeException("Forbidden: Profesor_academia no tiene permisos para este recurso");
        }
        // por defecto, forbiden
        throw new RuntimeException("Forbidden: rol no reconocido");
    }

    @Override
    public java.util.Map<String, JsonNode> sanitizeParams(UserClaims claims, Map<String, Object> endpoint, JsonNode pathParams, JsonNode query) {
        // Si es Admin_academia forzamos academy_id en query/path si existe en claims
        if (claims != null && claims.isAdminAcademia()) {
            if (claims.academiaId != null) {
                // Insertar academia_id en query si endpoint acepta 'academia_id'
                java.util.Map<String, JsonNode> out = new java.util.HashMap<>();
                com.fasterxml.jackson.databind.node.ObjectNode q = query != null && query.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode();
                q.put("academia_id", claims.academiaId);
                out.put("pathParams", pathParams == null ? om.createObjectNode() : pathParams);
                out.put("query", q);
                return out;
            }
        }
        return Map.of("pathParams", pathParams == null ? om.createObjectNode() : pathParams, "query", query == null ? om.createObjectNode() : query);
    }

    @Override
    public java.util.Map<String, Object> transformIfNeeded(UserClaims claims, Map<String, Object> endpoint, JsonNode pathParams, JsonNode query) {
        if (endpoint == null || claims == null) return null;
        String name = String.valueOf(endpoint.getOrDefault("name", ""));
        // Policy: if request is getAcademias (collection) and user is Admin_academia, transform to getAcademiaById
        if ("getAcademias".equals(name) && claims.isAdminAcademia()) {
            if (claims.academiaId == null) {
                throw new RuntimeException("Forbidden: admin_academia sin academia asociada");
            }
            // Intent: lookup endpoint metadata for getAcademiaById from the whitelist stored in OpenAICallApiService
            // We'll return a structure that ApiProxyService can use: endpointMetaName and overridden params
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("transform_to", "getAcademiaById");
            com.fasterxml.jackson.databind.node.ObjectNode newPath = om.createObjectNode();
            newPath.put("id", claims.academiaId);
            result.put("pathParams", newPath);
            result.put("query", query == null ? om.createObjectNode() : query);
            return result;
        }
        return null;
    }
}
