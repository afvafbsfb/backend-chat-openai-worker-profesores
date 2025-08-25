
package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.io.InputStream;
import java.util.*;

@Service
public class OpenAICallApiService {
    // Permite mockear el RestTemplate en tests
    protected RestTemplate createRestTemplate() { return new RestTemplate(); }
    // --- Configuración de renderizado de whitelist ---
    @Value("${whitelist.render.table:true}")
    private boolean whitelistRenderTable;

    @Value("${whitelist.render.narrative:true}")
    private boolean whitelistRenderNarrative;

    @Value("${whitelist.render.max-endpoints:0}")
    private int whitelistMaxEndpoints;

    // Helpers para renderizado Markdown y narrativa
    private static String join(List<Object> list) {
        if (list == null || list.isEmpty()) return "—";
        return list.stream().map(String::valueOf).reduce((a,b) -> a + ", " + b).orElse("—");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("|", "\\|");
    }

    // Devuelve la lista limitada según la config
    private List<Map<String, Object>> getWhitelistLimited() {
        if (whitelist == null) return List.of();
        if (whitelistMaxEndpoints <= 0 || whitelist.size() <= whitelistMaxEndpoints) return whitelist;
        return whitelist.subList(0, whitelistMaxEndpoints);
    }

    // Tabla Markdown con descripciones (lista limitada)
    public String renderWhitelistTableMarkdown(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) {
            return "\n\n### Endpoints permitidos (tabla)\n\n_No hay endpoints en la whitelist._\n";
        }
        StringBuilder sb = new StringBuilder("\n\n### Endpoints permitidos (tabla)\n\n");
        sb.append("| name | method | path | pathParams | query | body | description |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> ep : list) {
            String name   = String.valueOf(ep.getOrDefault("name", ""));
            String method = String.valueOf(ep.getOrDefault("method", ""));
            String path   = String.valueOf(ep.getOrDefault("path", ""));
            String desc   = String.valueOf(ep.getOrDefault("description", ""));
            @SuppressWarnings("unchecked") var pathParams = (List<Object>) ep.getOrDefault("pathParams", List.of());
            @SuppressWarnings("unchecked") var query      = (List<Object>) ep.getOrDefault("query", List.of());
            @SuppressWarnings("unchecked") var body       = (List<Object>) ep.getOrDefault("body", List.of());
            sb.append("| ")
              .append(escape(name)).append(" | ")
              .append(escape(method)).append(" | ")
              .append(escape(path)).append(" | ")
              .append(escape(join(pathParams))).append(" | ")
              .append(escape(join(query))).append(" | ")
              .append(escape(join(body))).append(" | ")
              .append(escape(desc)).append(" |\n");
        }
        return sb.toString();
    }

    // Narrativa con descripciones (lista limitada)
    public String renderWhitelistNarrativeWithDescriptions(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) {
            return "\n\n### Endpoints permitidos (descripción)\n\n_No hay endpoints en la whitelist._\n";
        }
        StringBuilder sb = new StringBuilder("\n\n### Endpoints permitidos (descripción)\n");
        for (Map<String, Object> ep : list) {
            String name   = String.valueOf(ep.getOrDefault("name", ""));
            String method = String.valueOf(ep.getOrDefault("method", ""));
            String path   = String.valueOf(ep.getOrDefault("path", ""));
            String desc   = String.valueOf(ep.getOrDefault("description", ""));
            @SuppressWarnings("unchecked") var pathParams = (List<Object>) ep.getOrDefault("pathParams", List.of());
            @SuppressWarnings("unchecked") var query      = (List<Object>) ep.getOrDefault("query", List.of());
            @SuppressWarnings("unchecked") var body       = (List<Object>) ep.getOrDefault("body", List.of());
            sb.append("- **").append(name).append("** — ").append(desc.isBlank() ? "(sin descripción)" : desc).append("\n")
              .append("  - `").append(method).append(" ").append(path).append("`\n");
            if (!pathParams.isEmpty()) sb.append("  - pathParams: ").append(join(pathParams)).append("\n");
            if (!query.isEmpty())      sb.append("  - query: ").append(join(query)).append("\n");
            if (!body.isEmpty())       sb.append("  - body: ").append(join(body)).append("\n");
        }
        return sb.toString();
    }

    // Sección combinada gobernada por flags y límite
    public String renderWhitelistSectionCombined() {
        List<Map<String, Object>> list = getWhitelistLimited();
        StringBuilder sb = new StringBuilder();
        if (whitelistRenderTable)    sb.append(renderWhitelistTableMarkdown(list));
        if (whitelistRenderNarrative) sb.append(renderWhitelistNarrativeWithDescriptions(list));
        return sb.toString();
    }
    /**
     * Renderiza la whitelist YAML como una tabla de endpoints para el prompt de sistema.
     */
    public String renderWhitelistTable() {
        if (whitelist == null || whitelist.isEmpty()) return "(No hay endpoints en la whitelist)";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nWhitelist de endpoints permitidos:\n");
        sb.append("| name           | method | path                | pathParams | query         |\n");
        sb.append("|----------------|--------|---------------------|------------|--------------|\n");
        for (Map<String, Object> ep : whitelist) {
            String name = String.valueOf(ep.getOrDefault("name", ""));
            String method = String.valueOf(ep.getOrDefault("method", ""));
            String path = String.valueOf(ep.getOrDefault("path", ""));
            String pathParams = ep.get("pathParams") != null ? ep.get("pathParams").toString() : "";
            String query = ep.get("query") != null ? ep.get("query").toString() : "";
            sb.append(String.format("| %-14s | %-6s | %-19s | %-10s | %-12s |\n",
                name, method, path, pathParams, query));
        }
        return sb.toString();
    }
    @Value("${openai.api.key}")
    private String openaiApiKey;

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private List<Map<String, Object>> whitelist;

    public OpenAICallApiService() {
        this(null);
    }

    // Constructor para tests: permite inyectar un InputStream alternativo (o null para usar el real)
    public OpenAICallApiService(InputStream whitelistInputStream) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream is = whitelistInputStream != null ? whitelistInputStream : new ClassPathResource("api-whitelist.yaml").getInputStream();
            Map<String, Object> yaml = mapper.readValue(is, new TypeReference<Map<String, Object>>(){});
            Object endpointsObj = yaml.get("endpoints");
            whitelist = new ArrayList<>();
            if (endpointsObj instanceof List<?>) {
                for (Object item : (List<?>) endpointsObj) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> endpoint = (Map<String, Object>) item;
                        whitelist.add(endpoint);
                    }
                }
            }
        } catch (Exception e) {
            whitelist = List.of();
            System.err.println("[ERROR] No se pudo cargar la whitelist: " + e.getMessage());
        }
    }

    public String callChatWithTools(List<Map<String, Object>> messages) throws Exception {
        // Define la tool genérica call_api
        Map<String, Object> callApiTool = Map.of(
            "type", "function",
            "function", Map.of(
                "name", "call_api",
                "description", "Llama a un endpoint permitido de la API de la academia.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Nombre lógico del endpoint (whitelist)"),
                        "method", Map.of("type", "string", "enum", List.of("GET","POST","PUT","DELETE")),
                        "pathParams", Map.of("type", "object"),
                        "query", Map.of("type", "object"),
                        "body", Map.of("type", "object")
                    ),
                    "required", List.of("name", "method")
                )
            )
        );
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4o");
        requestBody.put("temperature", 0.2);
        requestBody.put("messages", messages);
        requestBody.put("tools", List.of(callApiTool));
    RestTemplate restTemplate = createRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.set("Content-Type", "application/json");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_URL, entity, String.class);
        return response.getBody();
    }

    public Map<String, Object> getEndpointByName(String name) {
        for (Map<String, Object> ep : whitelist) {
            if (Objects.equals(ep.get("name"), name)) return ep;
        }
        return null;
    }
}
