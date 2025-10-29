package com.workers.profesores.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Service
public class AuthorizationServiceImpl implements AuthorizationService {
    private static final Logger logger = LoggerFactory.getLogger(AuthorizationServiceImpl.class);
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void checkAllowed(UserClaims claims, Map<String, Object> endpoint, String method, JsonNode pathParams, JsonNode query) {
        if (claims == null) throw new RuntimeException("Forbidden: no claims provided");
        if (endpoint == null) endpoint = Map.of();
        // If x-permissions present, honor allowed_roles if defined
        Object xp = endpoint.get("x-permissions");
        if (xp instanceof Map<?, ?>) {
            Map<?, ?> xperm = (Map<?, ?>) xp;
            Object allowed = xperm.get("allowed_roles");
            if (allowed instanceof java.util.List<?>) {
                @SuppressWarnings("unchecked") java.util.List<String> allowedRoles = (java.util.List<String>) allowed;
                // If the user has any allowed role, permit
                boolean ok = false;
                if (claims.roles != null) {
                    for (String r : claims.roles) {
                        if (allowedRoles.contains(r)) { ok = true; break; }
                    }
                }
                if (!ok) throw new RuntimeException("Forbidden: role not allowed by x-permissions");
            }
        }
        // Fallback/backwards-compatible behavior for role-specific logic
        if (claims.isAdminPlataforma()) return; // todo permitido
        if (claims.isAdminAcademia()) {
            // Denegar operaciones de gestión de academias si endpoint contiene create/delete on academias
            String path = String.valueOf(endpoint.getOrDefault("path", ""));
            if (path.contains("/academias") && ("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method) || "PUT".
                    equalsIgnoreCase(method))) {
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
        if (endpoint == null) endpoint = Map.of();
        com.fasterxml.jackson.databind.node.ObjectNode q = query != null && query.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode p = pathParams != null && pathParams.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) pathParams.deepCopy() : om.createObjectNode();
        // If x-permissions has enforced_filters, apply them
        Object xp = endpoint == null ? null : endpoint.get("x-permissions");
        if (xp instanceof Map<?, ?> && claims != null) {
            Map<?, ?> xperm = (Map<?, ?>) xp;
            Object enforced = xperm.get("enforced_filters");
            if (enforced instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked") Map<String, Object> enforcedMap = (Map<String, Object>) enforced;
                for (Map.Entry<String, Object> e : enforcedMap.entrySet()) {
                    String param = e.getKey();
                    String expr = String.valueOf(e.getValue());
                    // Support simple expression: current_user.academia_id or current_user.usuarioId
                    if ("current_user.academia_id".equals(expr) && claims.academiaId != null) {
                        q.put(param, claims.academiaId);
                    } else if ("current_user.usuarioId".equals(expr) && claims.usuarioId != null) {
                        q.put(param, claims.usuarioId);
                    }
                }
            }
        }
        // Backwards-compatible behaviour: if Admin_academia ensure academia_id
        if (claims != null && claims.isAdminAcademia() && claims.academiaId != null) {
            q.put("academia_id", claims.academiaId);
        }
        return Map.of("pathParams", p, "query", q);
    }

    @Override
    public java.util.Map<String, Object> transformIfNeeded(UserClaims claims, Map<String, Object> endpoint, JsonNode pathParams, JsonNode query) {
        if (endpoint == null || claims == null) return null;
        // Prefer operationId as canonical name
        String operationId = String.valueOf(endpoint.getOrDefault("operationId", endpoint.getOrDefault("name", "")));
        // Example policy: if a collection endpoint (operationId contains "listar" or returns Paginated) and user is Admin_academia, transform to item-level
        if ((operationId.toLowerCase().contains("listar") || operationId.toLowerCase().contains("list")) && claims.isAdminAcademia()) {
            // Need an associated item endpoint; we expect SpecLoader or other logic to have an endpoint named like 'obtener' counterpart
            if (claims.academiaId == null) {
                throw new RuntimeException("Forbidden: admin_academia sin academia asociada");
            }
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            // Heuristics: try to guess transform target by replacing 'listar' with 'obtener' or take from x-permissions.transform_to if present
            Object xp = endpoint.get("x-permissions");
            System.err.println("=== [AUTH-DEBUG] transformIfNeeded: operationId=" + operationId + ", hasXPermissions=" + (xp != null));
            logger.info("[DEBUG] transformIfNeeded: operationId={}, hasXPermissions={}", operationId, xp != null);
            if (xp instanceof Map<?, ?>) {
                Map<?, ?> xperm = (Map<?, ?>) xp;
                boolean hasTransformKey = xperm.containsKey("transform_to");
                Object transformTo = xperm.get("transform_to");
                System.err.println("=== [AUTH-DEBUG] x-permissions: hasTransformToKey=" + hasTransformKey + ", transformToValue=" + transformTo);
                logger.info("[DEBUG] x-permissions: hasTransformToKey={}, transformToValue={}", hasTransformKey, transformTo);
                // Check if transform_to key EXISTS (even if value is null) - null means "do not transform"
                if (hasTransformKey) {
                    result.put("transform_to", transformTo == null ? null : String.valueOf(transformTo));
                    System.err.println("=== [AUTH-DEBUG] Added transform_to to result: " + result.get("transform_to"));
                    logger.info("[DEBUG] Added transform_to to result: {}", result.get("transform_to"));
                }
            }
            if (!result.containsKey("transform_to")) {
                System.err.println("=== [AUTH-DEBUG] No transform_to in x-permissions, using guess");
                logger.info("[DEBUG] No transform_to in x-permissions, using guess");
                String guess = operationId.replace("listar", "obtener");
                // If guess looks like 'xxx.obtener_yyy' and yyy is plural (ends with 's'),
                // try a singular form 'yyy' -> 'yy' by removing trailing 's'. This handles
                // common operationId patterns like 'usuarios.listar_usuarios' -> 'usuarios.obtener_usuario'.
                try {
                    int dot = guess.indexOf('.');
                    if (dot >= 0) {
                        String prefix = guess.substring(0, dot + 1); // includes dot
                        String suffix = guess.substring(dot + 1);
                        if (suffix.contains("_")) {
                            int us = suffix.lastIndexOf('_');
                            String tail = suffix.substring(us + 1);
                            if (tail.endsWith("s") && tail.length() > 1) {
                                String singularTail = tail.substring(0, tail.length() - 1);
                                String candidate = prefix + suffix.substring(0, us + 1) + singularTail;
                                guess = candidate;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                result.put("transform_to", guess);
            }
            com.fasterxml.jackson.databind.node.ObjectNode newPath = om.createObjectNode();
            newPath.put("id", claims.academiaId);
            result.put("pathParams", newPath);
            result.put("query", query == null ? om.createObjectNode() : query);
            return result;
        }
        return null;
    }
}
