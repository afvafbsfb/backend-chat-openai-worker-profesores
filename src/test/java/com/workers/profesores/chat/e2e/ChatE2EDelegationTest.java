package com.workers.profesores.chat.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.List;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;


import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.delegation.secret=test-delegation-secret",
        "served.openapi.path=classpath:served-openapi.json"
})
public class ChatE2EDelegationTest {

    private static WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    public static void registerProperties(DynamicPropertyRegistry registry) {
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        registry.add("academia.api.baseurl", () -> "http://localhost:" + wireMockServer.port());
        // Provide a 256-bit (32-byte) delegation secret required by Nimbus MACSigner
        registry.add("jwt.delegation.secret", () -> "0123456789abcdef0123456789abcdef");
    }

    @AfterAll
    public static void teardown() {
        if (wireMockServer != null && wireMockServer.isRunning()) wireMockServer.stop();
    }

    @Test
    public void chatFlow_forwardsDelegatedTokenToAcademy() throws Exception {
        // Arrange: Academy mock expects any POST and captures the Authorization header
    // Stub any method to the academias path so the test focuses on header forwarding, not HTTP verb
    wireMockServer.stubFor(any(urlPathMatching("/academias.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"result\":\"ok\"}")));

        // Build minimal chat request body that triggers a call_api tool from OpenAI.
        // For simplicity we craft a request that the ChatService will accept and then
        // OpenAICallApiService will call ApiProxyService which will use the delegated token.

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
    // Provide an Authorization header for the incoming user token (must be a JWT so DevJwtConfig can decode it)
    // Build a permissive unsigned JWT payload with roles that allow listing academias
    ObjectMapper om = new ObjectMapper();
    String subJson = "{\"usuario_id\":1}";
    var payloadMap = Map.of(
        "sub", subJson,
        "roles", List.of("Admin_academia"),
        "academia_id", 1
    );
    String payloadJson = om.writeValueAsString(payloadMap);
    String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
    String headerB64 = "eyJhbGciOiJub25lIn0"; // {"alg":"none"}
    String incomingJwt = headerB64 + "." + payloadB64;
    headers.set("Authorization", "Bearer " + incomingJwt);

        String requestJson = "{\"messages\":[{\"role\":\"user\",\"content\":\"List academias\"}]}";

        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        // Act: call the running application /chat endpoint
        ResponseEntity<String> response = restTemplate.postForEntity("http://localhost:" + port + "/chat", entity, String.class);

        // Assert: application responded OK
        assertEquals(HttpStatus.OK, response.getStatusCode());

    // Verify WireMock received a request to academias and capture the Authorization header
    wireMockServer.verify(anyRequestedFor(urlPathMatching("/academias.*")));

        // Extract the header from the request made to WireMock
        // Search recorded serve events for the first one targeting /academias
        LoggedRequest found = null;
        for (var se : wireMockServer.getAllServeEvents()) {
            var req = se.getRequest();
            if (req.getUrl() != null && req.getUrl().startsWith("/academias")) { found = req; break; }
        }
        assertNotNull(found, "No se recibió ninguna petición a /academias en WireMock");
        String delegatedAuth = found.getHeader("Authorization");
        assertNotNull(delegatedAuth);
        assertTrue(delegatedAuth.startsWith("Bearer "));

        String token = delegatedAuth.substring(7);
        // Decode the JWT and assert it contains actor claim 'chat-backend'
        SignedJWT signedJWT = SignedJWT.parse(token);
        String actor = (String) signedJWT.getJWTClaimsSet().getClaim("actor");
        assertEquals("chat-backend", actor);
    }
}
