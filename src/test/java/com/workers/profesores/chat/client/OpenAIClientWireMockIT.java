package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.ResponseEntity;
import com.workers.profesores.chat.service.ApiProxyService;
import com.workers.profesores.chat.service.OpenAICallApiService;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.*;

public class OpenAIClientWireMockIT {
	private static WireMockServer wiremock;

	@BeforeAll
	static void start() {
		wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wiremock.start();
		WireMock.configureFor("localhost", wiremock.port());
	}

	@AfterAll
	static void stop() {
		if (wiremock != null) wiremock.stop();
	}

	@Test
	void createResponse_againstWireMock_returnsParsedJson() {
		// Stub de la API de OpenAI (respuesta mínima con choices.message.content)
		wiremock.stubFor(post(urlPathEqualTo("/v1/responses"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"id\":\"res-1\",\"object\":\"response\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello from wiremock\"}}]}")
				));

		OpenAIClient client = new OpenAIClient();
		// apuntar al wiremock local
		ReflectionTestUtils.setField(client, "apiUrl", wiremock.baseUrl() + "/v1/responses");
		ReflectionTestUtils.setField(client, "apiKey", "test-key");

		JsonNode resp = client.createResponse(List.of(Map.of("role", "user", "content", "Hola")), List.of());
		assertNotNull(resp, "Respuesta no debe ser null");
		assertTrue(resp.has("choices"), "Respuesta debe contener choices");
		assertTrue(resp.get("choices").isArray());
		assertEquals("Hello from wiremock", resp.get("choices").get(0).get("message").get("content").asText());
	}

    @Test
    void createResponse_withToolCalls_presentInJson() {
	wiremock.stubFor(post(urlPathEqualTo("/v1/responses"))
		.willReturn(aResponse()
			.withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"id\":\"res-2\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"See tools\",\"tool_calls\":[{\"id\":\"t-1\",\"function\":{\"name\":\"call_api\",\"arguments\":\"{}\"}}]}}]}")
		));

	OpenAIClient client = new OpenAIClient();
	ReflectionTestUtils.setField(client, "apiUrl", wiremock.baseUrl() + "/v1/responses");
	ReflectionTestUtils.setField(client, "apiKey", "test-key");

	JsonNode resp = client.createResponse(List.of(Map.of("role", "user", "content", "Do tools")), List.of());
	assertNotNull(resp);
	JsonNode choice = resp.get("choices").get(0);
	JsonNode message = choice.get("message");
	assertEquals("See tools", message.get("content").asText());
	assertTrue(message.has("tool_calls"));
	assertTrue(message.get("tool_calls").isArray());
	assertEquals("t-1", message.get("tool_calls").get(0).get("id").asText());
    }

    @Test
    void createResponse_http500_throwsException() {
	wiremock.stubFor(post(urlPathEqualTo("/v1/responses"))
		.willReturn(aResponse().withStatus(500).withBody("Internal error")));

	OpenAIClient client = new OpenAIClient();
	ReflectionTestUtils.setField(client, "apiUrl", wiremock.baseUrl() + "/v1/responses");
	ReflectionTestUtils.setField(client, "apiKey", "test-key");

	assertThrows(HttpServerErrorException.class, () -> client.createResponse(List.of(Map.of("role", "user", "content", "Hola")), List.of()));
    }

    @Test
    void openAICallApiService_processesToolCall_andCallsApiProxy() throws Exception {
	// OpenAI stub with a tool_call pointing to endpoint "mi-endpoint"
	String toolCallJson = "{\"id\":\"t-1\",\"function\":{\"name\":\"call_api\",\"arguments\":\"{\\\"name\\\":\\\"mi-endpoint\\\",\\\"method\\\":\\\"GET\\\",\\\"pathParams\\\":{},\\\"query\\\":{},\\\"body\\\":{}}\"}}";
	String openaiResp = "{\"id\":\"res-3\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"tooling\",\"tool_calls\":[]}}],\"tool_calls\":[] }";
	// We will craft a response where message contains tool_calls as a list with that toolCall
	openaiResp = "{\"id\":\"res-3\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"tooling\",\"tool_calls\":[" + toolCallJson + "]}}]}";

	wiremock.stubFor(post(urlPathEqualTo("/v1/responses"))
		.willReturn(aResponse()
			.withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody(openaiResp)
		));

	// Prepare a mock ApiProxyService that will be called by OpenAICallApiService
	ApiProxyService apiProxyMock = Mockito.mock(ApiProxyService.class);
	Mockito.when(apiProxyMock.executeWhitelistedCallWithResponse(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any()))
		.thenReturn(ResponseEntity.ok("{\"ok\":true}\""));

	// Create a minimal whitelist YAML input with endpoint named 'mi-endpoint'
	String yaml = "endpoints:\n  - name: mi-endpoint\n    method: GET\n    path: /dummy\n";
	java.io.InputStream yamlStream = new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));

	// Create service instance with mock and test whitelist
	OpenAICallApiService svc = new OpenAICallApiService(apiProxyMock, yamlStream);
	// Point the service to the wiremock endpoint and set an api key
	ReflectionTestUtils.setField(svc, "openaiApiUrl", wiremock.baseUrl() + "/v1/responses");
	ReflectionTestUtils.setField(svc, "openaiApiKey", "test-key");

	// Build messages to pass (format expected by service)
	java.util.List<java.util.Map<String,Object>> messages = new java.util.ArrayList<>();
	java.util.Map<String,Object> userMsg = new java.util.HashMap<>();
	userMsg.put("role", "user");
	userMsg.put("content", "Call API");
	messages.add(userMsg);

	String result = svc.callChatWithTools(messages);

	// Verify ApiProxyService was invoked and result includes API response (or at least returns something)
	Mockito.verify(apiProxyMock, Mockito.atLeastOnce()).executeWhitelistedCallWithResponse(Mockito.anyMap(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
	assertNotNull(result);
    }
}


