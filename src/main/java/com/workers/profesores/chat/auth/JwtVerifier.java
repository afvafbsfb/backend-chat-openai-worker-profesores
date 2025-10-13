package com.workers.profesores.chat.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

@Component
public class JwtVerifier {
    private final JwtDecoder jwtDecoder;

    @Value("${jwt.delegation.secret}")
    private String jwtDelegationSecret;

    public JwtVerifier(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    public UserClaims verify(String authorizationHeader) throws JwtException {
        if (authorizationHeader == null || !authorizationHeader.toLowerCase().startsWith("bearer ")) {
            throw new JwtException("No Authorization Bearer header provided");
        }
        String token = authorizationHeader.substring(7).trim();
        Jwt jwt = jwtDecoder.decode(token);
        Map<String,Object> claims = jwt.getClaims();
        Long usuarioId = null;
        Integer tokenVersion = null;
        Object subObj = claims.get("sub");
        if (subObj != null) {
            try {
                String subStr = String.valueOf(subObj);
                // Try parse as JSON {"usuario_id":..., "token_version":...}
                if (subStr.trim().startsWith("{")) {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    try {
                        java.util.Map<String,Object> m = om.readValue(subStr, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){});
                        if (m.get("usuario_id") != null) {
                            usuarioId = Long.parseLong(String.valueOf(m.get("usuario_id")));
                        }
                        if (m.get("token_version") != null) {
                            try { tokenVersion = Integer.parseInt(String.valueOf(m.get("token_version"))); } catch (Exception ignore) { tokenVersion = null; }
                        }
                    } catch (Exception jex) {
                        // fallback to numeric subject
                        try { usuarioId = Long.parseLong(subStr); } catch (Exception ignore) { usuarioId = null; }
                    }
                } else {
                    try { usuarioId = Long.parseLong(subStr); } catch (Exception e) { usuarioId = null; }
                }
            } catch (Exception e) { usuarioId = null; }
        }
        if (usuarioId != null) {
            System.out.println("DEBUG: UsuarioId obtenido del token JWT: " + usuarioId);
        } else {
            System.out.println("DEBUG: No se pudo obtener UsuarioId del token JWT.");
        }
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles") instanceof List ? (List<String>) claims.get("roles") : List.of();
        Integer academiaId = claims.get("academia_id") == null ? null : Integer.parseInt(String.valueOf(claims.get("academia_id")));
        Integer profesorUsuarioId = claims.get("profesor_usuario_id") == null ? null : Integer.parseInt(String.valueOf(claims.get("profesor_usuario_id")));
        String actor = claims.get("actor") == null ? null : String.valueOf(claims.get("actor"));
        return new UserClaims(usuarioId, roles, academiaId, profesorUsuarioId, actor, tokenVersion);
    }
}
