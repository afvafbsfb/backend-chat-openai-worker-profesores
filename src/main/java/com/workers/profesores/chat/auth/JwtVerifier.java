package com.workers.profesores.chat.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class JwtVerifier {
    private final JwtDecoder jwtDecoder;

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
        if (claims.get("sub") != null) {
            try { usuarioId = Long.parseLong(String.valueOf(claims.get("sub"))); } catch (Exception e) { usuarioId = null; }
        }
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles") instanceof List ? (List<String>) claims.get("roles") : List.of();
        Integer academiaId = claims.get("academia_id") == null ? null : Integer.parseInt(String.valueOf(claims.get("academia_id")));
        Integer profesorUsuarioId = claims.get("profesor_usuario_id") == null ? null : Integer.parseInt(String.valueOf(claims.get("profesor_usuario_id")));
        return new UserClaims(usuarioId, roles, academiaId, profesorUsuarioId);
    }
}
