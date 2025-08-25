package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AcademiaClient {


    @Value("${academia.api.key}")
    private String apiKey;
    public JsonNode getAlumnoById(String id) {
        // TODO: Implementar llamada real a la API de la academia
        return null;
    }
    public JsonNode listAlumnos(int page, int size, String q) {
        // Ignorar paginación y devolver la lista completa
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Api-Key", apiKey);
        headers.set("accept", "*/*");
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        try {
        // La URL ya no se usa aquí, se gestiona desde ApiProxyService y la whitelist
        // Este método solo se mantiene para compatibilidad o tests
        return null;
        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException ex) {
            System.err.println("[AcademiaClient] Error al llamar a la API de alumnos: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString());
            com.fasterxml.jackson.databind.node.ObjectNode errorNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            errorNode.put("error", "API Academia error: " + ex.getStatusCode());
            errorNode.put("details", ex.getResponseBodyAsString());
            return errorNode;
        } catch (Exception ex) {
            System.err.println("[AcademiaClient] Error inesperado: " + ex.getMessage());
            com.fasterxml.jackson.databind.node.ObjectNode errorNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            errorNode.put("error", "Error inesperado en AcademiaClient");
            errorNode.put("details", ex.getMessage());
            return errorNode;
        }
    }
}
