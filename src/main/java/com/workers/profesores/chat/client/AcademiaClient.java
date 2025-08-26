package com.workers.profesores.chat.client;


import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AcademiaClient {
    private static final Logger logger = LoggerFactory.getLogger(AcademiaClient.class);

    @Value("${backend.debug:false}")
    private boolean debug;

    @Value("${academia.api.baseurl}")
    private String baseUrl;

    @Autowired
    private RestTemplate restTemplate;


    @Value("${academia.api.key}")
    private String apiKey;
    // --- Métodos generados a partir de la whitelist ---
    public JsonNode healthCheck() { if (debug) System.out.println("[AcademiaClient][DEBUG] healthCheck()"); return null; }
    public JsonNode getTurnosLibres(int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] getTurnosLibres page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/turnos/libres")
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al llamar a la API de turnos libres: {}", e.getMessage(), e);
            throw new RuntimeException("Error al obtener turnos libres", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para turnos libres: {}", e.getMessage(), e);
            throw e;
        }
    }
    public JsonNode inscribirAlumno(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] inscribirAlumno body=" + body); return null; }
    public JsonNode registrarPago(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] registrarPago body=" + body); return null; }
    public JsonNode getTurnosActivos() { if (debug) System.out.println("[AcademiaClient][DEBUG] getTurnosActivos()"); return null; }
    public JsonNode getTarifas(int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] getTarifas page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/tarifas")
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al llamar a la API de tarifas: {}", e.getMessage(), e);
            throw new RuntimeException("Error al obtener tarifas", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para tarifas: {}", e.getMessage(), e);
            throw e;
        }
    }
    public JsonNode crearTarifa(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] crearTarifa body=" + body); return null; }
    public JsonNode actualizarTarifa(String tarifa_id, JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] actualizarTarifa tarifa_id=" + tarifa_id + ", body=" + body); return null; }
    public JsonNode eliminarTarifa(String tarifa_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] eliminarTarifa tarifa_id=" + tarifa_id); return null; }
    public JsonNode getAlumnosTurno(String turno_id, int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnosTurno turno_id=" + turno_id + ", page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/turnos/" + turno_id + "/alumnos")
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al llamar a la API de alumnos del turno {}: {}", turno_id, e.getMessage(), e);
            throw new RuntimeException("Error al obtener alumnos del turno", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para alumnos del turno {}: {}", turno_id, e.getMessage(), e);
            throw e;
        }
    }
    public JsonNode bajaInscripcion(String inscripcion_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] bajaInscripcion inscripcion_id=" + inscripcion_id); return null; }
    public JsonNode getAlumnos(int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnos page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/alumnos")
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al llamar a la API de alumnos: {}", e.getMessage(), e);
            throw new RuntimeException("Error al obtener alumnos", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para alumnos: {}", e.getMessage(), e);
            throw e;
        }
    }
    public JsonNode getAlumnoById(String alumno_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnoById alumno_id=" + alumno_id); return null; }
    public JsonNode buscarAlumnosPorNombre(String nombre, int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] buscarAlumnosPorNombre nombre=" + nombre + ", page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/alumnos/buscar")
                .queryParam("nombre", nombre)
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al buscar alumnos por nombre '{}': {}", nombre, e.getMessage(), e);
            throw new RuntimeException("Error al buscar alumnos por nombre", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para búsqueda de alumnos por nombre '{}': {}", nombre, e.getMessage(), e);
            throw e;
        }
    }
    public JsonNode crearEmpresa(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] crearEmpresa body=" + body); return null; }
    public JsonNode getEmpresas(int page, int size) {
        if (debug) System.out.println("[AcademiaClient][DEBUG] getEmpresas page=" + page + ", size=" + size);
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/empresas")
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    JsonNode.class
            );
            JsonNode apiResp = response.getBody();
            return mapPagedResponse(apiResp, page, size);
        } catch (RestClientException e) {
            logger.error("Error al llamar a la API de empresas: {}", e.getMessage(), e);
            throw new RuntimeException("Error al obtener empresas", e);
        } catch (IllegalArgumentException e) {
            logger.error("Respuesta de paginación inválida para empresas: {}", e.getMessage(), e);
            throw e;
        }
    }
    /**
     * Mapea una respuesta paginada de la API a un formato estándar con list, total, page, size, hasMore.
     * Espera que la respuesta de la API tenga al menos: 'list' (array) y 'total' (entero).
     */
    public JsonNode mapPagedResponse(JsonNode apiResponse, int page, int size) {
        if (apiResponse == null || !apiResponse.has("list") || !apiResponse.has("total")) {
            throw new IllegalArgumentException("Respuesta de API no válida para paginación");
        }
        int total = apiResponse.get("total").asInt(0);
        boolean hasMore = (page + 1) * size < total;
        // Construir el objeto estándar
        com.fasterxml.jackson.databind.node.ObjectNode result =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        result.set("list", apiResponse.get("list"));
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", hasMore);
        // Puedes copiar otros campos si lo necesitas
        return result;
    }
    public JsonNode getEmpresaById(String empresa_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] getEmpresaById empresa_id=" + empresa_id); return null; }
}
