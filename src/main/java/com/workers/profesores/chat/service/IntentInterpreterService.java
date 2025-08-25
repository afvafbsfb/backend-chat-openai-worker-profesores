package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Service
public class IntentInterpreterService {
    @Value("${backend.debug:false}")
    private boolean debug;
    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    public java.util.List<Map<String, Object>> interpretarIntencion(String textoUsuario) {
        if (debug) {
            System.out.println("[IntentInterpreterService][DEBUG] interpretarIntencion llamado con textoUsuario: " + textoUsuario);
        }
        String prompt = "Eres un asistente que interpreta intenciones de usuario para una API REST de gestión de academias. El usuario puede pedir varias cosas a la vez (por ejemplo: listar alumnos y turnos, o inscribir y registrar pago). Devuelve SIEMPRE SOLO el array de objetos JSON, sin explicaciones, sin texto adicional, sin comillas invertidas, sin Markdown, solo el array JSON, uno por cada intención detectada, con los siguientes campos en cada objeto:\n- comando: acción principal (listar, buscar, crear, actualizar, eliminar, inscribir, registrar_pago, dar_baja, obtener, contar)\n- entidad: entidad principal (alumno, turno, tarifa, inscripción, pago, empresa)\n- filtros: objeto con parámetros relevantes (nombre, id, etc.)\n- flags: lista de opciones adicionales (activos, libres, etc.)\n\nEjemplos:\nUsuario: 'Quiero ver todos los alumnos activos y los turnos libres'\nRespuesta:\n[\n  {\n    \"comando\": \"listar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": [\"activos\"]\n  },\n  {\n    \"comando\": \"listar\",\n    \"entidad\": \"turno\",\n    \"filtros\": {},\n    \"flags\": [\"libres\"]\n  }\n]\n\nUsuario: 'Inscribe a Juan Pérez en el turno de los lunes y registra un pago de 50 euros'\nRespuesta:\n[\n  {\n    \"comando\": \"inscribir\",\n    \"entidad\": \"inscripcion\",\n    \"filtros\": {\"nombre_alumno\": \"Juan Pérez\", \"turno\": \"lunes\"},\n    \"flags\": []\n  },\n  {\n    \"comando\": \"registrar_pago\",\n    \"entidad\": \"pago\",\n    \"filtros\": {\"nombre_alumno\": \"Juan Pérez\", \"importe\": 50},\n    \"flags\": []\n  }\n]\n\nUsuario: 'Dame las tarifas y empresas'\nRespuesta:\n[\n  {\n    \"comando\": \"listar\",\n    \"entidad\": \"tarifa\",\n    \"filtros\": {},\n    \"flags\": []\n  },\n  {\n    \"comando\": \"listar\",\n    \"entidad\": \"empresa\",\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\n\nUsuario: 'Quiero que me devuelvas la lista de alumnos y el total'\nRespuesta:\n[\n  {\n    \"comando\": \"listar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": []\n  },\n  {\n    \"comando\": \"contar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\nUsuario: '¿Cuántos alumnos hay?'\nRespuesta:\n[\n  {\n    \"comando\": \"contar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\nUsuario: 'Dame el número de alumnos'\nRespuesta:\n[\n  {\n    \"comando\": \"contar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\nUsuario: 'El total de alumnos'\nRespuesta:\n[\n  {\n    \"comando\": \"contar\",\n    \"entidad\": \"alumno\",\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\n\nSi la intención no es clara, responde con:\n[\n  {\n    \"comando\": \"desconocido\",\n    \"entidad\": null,\n    \"filtros\": {},\n    \"flags\": []\n  }\n]\n\nAhora analiza esta entrada:\n'" + textoUsuario.trim() + "'";
        if (debug) {
            System.out.println("[IntentInterpreterService][DEBUG] Prompt generado: " + prompt);
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("temperature", 0.3);
            requestBody.put("messages", java.util.List.of(
                    Map.of("role", "user", "content", prompt)
            ));
            if (debug) {
                System.out.println("[IntentInterpreterService][DEBUG] Payload enviado a OpenAI: " + requestBody);
            }
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(requestBody, headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_URL, entity, String.class);
            if (debug) {
                System.out.println("[IntentInterpreterService][DEBUG] Respuesta recibida de OpenAI: " + response.getBody());
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());
            String json = root.path("choices").get(0).path("message").path("content").asText();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            if (debug) {
                System.out.println("[IntentInterpreterService][DEBUG] Array JSON extraído: " + json);
            }
            return mapper.readValue(json, java.util.List.class);
        } catch (Exception e) {
            if (debug) {
                System.out.println("[IntentInterpreterService][DEBUG] Excepción: " + e.getMessage());
                e.printStackTrace();
            }
            return java.util.List.of();
        }
    }
}
