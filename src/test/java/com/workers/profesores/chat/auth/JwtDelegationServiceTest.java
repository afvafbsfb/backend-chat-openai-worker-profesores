package com.workers.profesores.chat.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JwtDelegationServiceTest {

    // Helper to set private fields when not running under Spring
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void createDelegatedAuthorizationHeader_includesTokenVersionAndSubjectJson() throws Exception {
        JwtDelegationService svc = new JwtDelegationService();
        // MACSigner in Nimbus requires a sufficiently long secret (32 bytes for HS256)
        setField(svc, "delegationSecret", "01234567890123456789012345678901");
        setField(svc, "expirationMinutes", 5);

    UserClaims claims = new UserClaims(42L, List.of("Admin_plataforma"), 1, null, null, 7);
        String header = svc.createDelegatedAuthorizationHeader(claims);
        assertNotNull(header);
        assertTrue(header.startsWith("Bearer "));
        String token = header.substring("Bearer ".length());

        SignedJWT parsed = SignedJWT.parse(token);
        JWTClaimsSet cs = parsed.getJWTClaimsSet();

        Object tv = cs.getClaim("token_version");
        assertNotNull(tv, "token_version claim should be present");
        assertEquals(7, ((Number) tv).intValue());

        String sub = cs.getSubject();
        assertNotNull(sub, "sub should be present and contain JSON");
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> subMap = om.readValue(sub, new TypeReference<Map<String,Object>>(){});
        assertEquals(42L, ((Number)subMap.get("usuario_id")).longValue());
        assertEquals(7, ((Number)subMap.get("token_version")).intValue());
    }
}
