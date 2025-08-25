package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIClientTest {
    private OpenAIClient client;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        client = new OpenAIClient();
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "apiUrl", "https://fake.openai.com/v1/test");
    }

    @Test
    void testCreateResponse_sendsCorrectRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fakeResponse = mapper.readTree("{\"id\":\"resp-1\"}");
        Mockito.when(mockRestTemplate.postForObject(Mockito.anyString(), Mockito.any(), Mockito.eq(JsonNode.class)))
                .thenReturn(fakeResponse);
        List<String> messages = List.of("msg1");
        List<String> tools = List.of("tool1");
        JsonNode result = client.createResponse(messages, tools);
        assertEquals("resp-1", result.get("id").asText());
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        Mockito.verify(mockRestTemplate).postForObject(Mockito.eq("https://fake.openai.com/v1/test"), captor.capture(), Mockito.eq(JsonNode.class));
        HttpEntity entity = captor.getValue();
        assertTrue(entity.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON));
        assertEquals("Bearer test-key", entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }
}
