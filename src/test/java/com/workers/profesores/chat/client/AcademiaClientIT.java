package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AcademiaClientIT {
    private static WireMockServer wiremock;

    @Autowired
    private AcademiaClient client;

    @BeforeAll
    static void startWiremock() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
        WireMock.configureFor("localhost", wiremock.port());
    }

    @AfterAll
    static void stopWiremock() {
        if (wiremock != null) wiremock.stop();
    }

    @Test
    void testGetAlumnos_integration_withWireMock() {
    String resp = "{\"list\":[{\"id\":1,\"nombre\":\"A\"}],\"total\":1}";
        wiremock.stubFor(get(urlPathEqualTo("/alumnos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/json").withBody(resp)));

        // Point the client baseUrl to WireMock and set dummy apiKey
        ReflectionTestUtils.setField(client, "baseUrl", "http://localhost:" + wiremock.port());
        ReflectionTestUtils.setField(client, "apiKey", "dummy-key");

        JsonNode result = client.getAlumnos(0, 10);
        assertNotNull(result);
        assertEquals(1, result.get("total").asInt());
        assertTrue(result.get("list").isArray());
    }
}
