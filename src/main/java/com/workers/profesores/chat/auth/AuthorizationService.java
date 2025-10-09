package com.workers.profesores.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface AuthorizationService {
    // Lanza RuntimeException (Forbidden) si no permitido
    void checkAllowed(UserClaims claims, Map<String,Object> endpoint, String method, JsonNode pathParams, JsonNode query);

    // Ajusta parámetros (por ejemplo force academy_id) y devuelve un pair (pathParams, query)
    java.util.Map<String, JsonNode> sanitizeParams(UserClaims claims, Map<String,Object> endpoint, JsonNode pathParams, JsonNode query);

    // Si la acción necesita transformarse (por ejemplo collection -> item según claims), devuelve un mapa con keys:
    // { "endpoint": Map<String,Object> endpointMeta, "pathParams": JsonNode, "query": JsonNode }
    // Si no necesita transformarse devuelve null o el mismo endpoint.
    java.util.Map<String,Object> transformIfNeeded(UserClaims claims, Map<String,Object> endpoint, JsonNode pathParams, JsonNode query);
}
