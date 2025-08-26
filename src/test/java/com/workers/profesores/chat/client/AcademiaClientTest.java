package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AcademiaClientTest {
    private AcademiaClient client;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        client = new AcademiaClient();
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "baseUrl", "https://fake.academia.com/api");
        ReflectionTestUtils.setField(client, "restTemplate", mockRestTemplate);
    }

    @Test
    void testListAlumnos_returnsBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fakeResponse = mapper.readTree("{\"list\":[1,2,3],\"total\":3}");
        ResponseEntity<JsonNode> responseEntity = ResponseEntity.ok(fakeResponse);
        Mockito.when(mockRestTemplate.exchange(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(HttpEntity.class),
                Mockito.eq(JsonNode.class)
        )).thenReturn(responseEntity);
        JsonNode result = client.getAlumnos(0, 10);
        assertNotNull(result);
        assertEquals(3, result.get("total").asInt());
        assertTrue(result.get("list").isArray());
    }
}
