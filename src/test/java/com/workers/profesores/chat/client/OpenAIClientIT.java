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

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OpenAIClientIT {
    private static WireMockServer wiremock;

    @Autowired
    private OpenAIClient client;

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
    void testCreateResponse_integration_withWireMock() {
        String fake = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hola desde WireMock\"}}]}";
        wiremock.stubFor(post(urlPathEqualTo("/v1/responses"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/json").withBody(fake)));

        ReflectionTestUtils.setField(client, "apiKey", "dummy-key");
        ReflectionTestUtils.setField(client, "apiUrl", "http://localhost:" + wiremock.port() + "/v1/responses");

        List<String> messages = List.of("msg1");
        List<String> tools = List.of("tool1");

        JsonNode result = client.createResponse(messages, tools);
        assertNotNull(result);
        assertTrue(result.has("choices"));
    }
}
