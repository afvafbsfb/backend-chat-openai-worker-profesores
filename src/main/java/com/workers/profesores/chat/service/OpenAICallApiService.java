
package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
    private ApiProxyService apiProxyService;
    @Value("${backend.debug:false}")
    private boolean debug;
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
        // Para Spring: ApiProxyService será inyectado después
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream is = new ClassPathResource("api-whitelist.yaml").getInputStream();
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

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAICallApiService(ApiProxyService apiProxyService) {
        this();
        this.apiProxyService = apiProxyService;
    }

    // Constructor para tests: permite inyectar un InputStream alternativo (o null para usar el real)
    public OpenAICallApiService(ApiProxyService apiProxyService, InputStream whitelistInputStream) {
        this.apiProxyService = apiProxyService;
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

    @SuppressWarnings("unchecked")
    public String callChatWithTools(List<Map<String, Object>> messages) throws Exception {
        return callChatWithTools(messages, null);
    }

    @SuppressWarnings("unchecked")
    public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) throws Exception {
        if (debug) {
            System.out.println("[OpenAICallApiService][DEBUG] callChatWithTools llamado con mensajes: " + messages);
        }
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
    List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
        ObjectMapper mapper = new ObjectMapper();
        int maxIterations = 10; // Evita bucles infinitos
        for (int iter = 0; iter < maxIterations; iter++) {
            if (xmlLogger != null) xmlLogger.addStep("OpenAI", "Llamada a OpenAI (iteración " + iter + ") - mensajes: " + messagesToLogString(currentMessages));
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o");
            requestBody.put("temperature", 0.2);
            requestBody.put("messages", currentMessages);
            requestBody.put("tools", List.of(callApiTool));
            if (debug) {
                System.out.println("[OpenAICallApiService][DEBUG] Iteración " + iter + " - Payload enviado a OpenAI: " + requestBody);
            }
            RestTemplate restTemplate = createRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_URL, entity, String.class);
            if (debug) {
                System.out.println("[OpenAICallApiService][DEBUG] Respuesta recibida de OpenAI: " + response.getBody());
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAI", "Respuesta de OpenAI: " + response.getBody());
            Map<String, Object> resp = mapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>(){});
            // Procesar la respuesta de OpenAI
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                return response.getBody(); // Respuesta inesperada
            }
            Map<String, Object> choice = choices.get(0);
            Object messageObj = choice.get("message");
            if (!(messageObj instanceof Map)) {
                return response.getBody(); // Respuesta inesperada
            }
            Map<String, Object> message = (Map<String, Object>) messageObj;
            // Si hay tool_calls, procesarlas
            if (message.containsKey("tool_calls")) {
                Object toolCallsObj = message.get("tool_calls");
                if (!(toolCallsObj instanceof List)) {
                    return response.getBody(); // Respuesta inesperada
                }
                List<?> toolCallsRaw = (List<?>) toolCallsObj;
                // Insertar los mensajes tool justo después del assistant con tool_calls
                currentMessages.add(message); // Añadir el mensaje assistant con tool_calls
                List<Map<String, Object>> toolMsgs = new ArrayList<>();
                for (Object toolCallObj : toolCallsRaw) {
                    if (!(toolCallObj instanceof Map)) continue;
                    Map<String, Object> toolCall = (Map<String, Object>) toolCallObj;
                    Object functionObj = toolCall.get("function");
                    if (!(functionObj instanceof Map)) continue;
                    Map<String, Object> function = (Map<String, Object>) functionObj;
                    String toolName = (String) function.get("name");
                    String toolId = (String) toolCall.get("id");
                    String argumentsJson = (String) function.get("arguments");
                    Map<String, Object> args = mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>(){});
                    if (debug) {
                        System.out.println("[OpenAICallApiService][DEBUG] Ejecutando tool_call: " + toolName + " args=" + args);
                    }
                    // Ejecutar la tool (solo soportamos call_api)
                    String toolResult = "";
                    if ("call_api".equals(toolName)) {
                        String endpointName = (String) args.get("name");
                        String methodFromModel = (String) args.get("method");
                        JsonNode pathParams = mapper.valueToTree(args.get("pathParams"));
                        JsonNode query = mapper.valueToTree(args.get("query"));
                        JsonNode body = mapper.valueToTree(args.get("body"));
                        Map<String, Object> endpoint = getEndpointByName(endpointName);
                        if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Llamada a API: " + endpointName + " (" + methodFromModel + ")");
                        if (endpoint == null) {
                            toolResult = "[ERROR] Endpoint no permitido o no encontrado: " + endpointName;
                            if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Endpoint no permitido o no encontrado: " + endpointName);
                        } else {
                            try {
                                org.springframework.http.ResponseEntity<String> respApi = null;
                                try {
                                    respApi = apiProxyService.executeWhitelistedCallWithResponse(endpoint, methodFromModel, pathParams, query, body);
                                    toolResult = respApi.getBody() == null ? "{}" : respApi.getBody();
                                    if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Respuesta de API: " + respApi.getStatusCode());
                                } catch (org.springframework.web.client.HttpStatusCodeException ex2) {
                                    toolResult = "[ERROR] Código de error: " + ex2.getStatusCode();
                                    if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Error de API: " + ex2.getStatusCode());
                                }
                            } catch (Exception ex2) {
                                toolResult = "[ERROR] Excepción al llamar API: " + ex2.getMessage();
                                if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Excepción al llamar API: " + ex2.getMessage());
                            }
                        }
                    } else {
                        toolResult = "[ERROR] Tool no soportada: " + toolName;
                        if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Tool no soportada: " + toolName);
                    }
                    // Crear el mensaje de tipo tool
                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("content", toolResult);
                    toolMsg.put("tool_call_id", toolId);
                    toolMsgs.add(toolMsg);
                }
                currentMessages.addAll(toolMsgs); // Insertar los tool_msgs justo después del assistant
                // continuar con la siguiente iteración del bucle principal
                continue;
            } else {
                // No hay tool_calls, respuesta final
                currentMessages.add(message); // Añadir el mensaje assistant final
                return response.getBody();
            }
        }
        // Si se alcanza este punto, se ha superado el número máximo de iteraciones
        return "[ERROR] Demasiadas iteraciones de tool calling (posible bucle infinito)";
    }

    // Método utilitario para buscar endpoint por nombre (restaurado)
    public Map<String, Object> getEndpointByName(String name) {
        if (whitelist == null) return null;
        for (Map<String, Object> ep : whitelist) {
            if (Objects.equals(ep.get("name"), name)) return ep;
        }
        return null;
    }
    // Utilidad para loggear los mensajes de OpenAI de forma legible
    // Utilidad para loggear los mensajes de OpenAI de forma legible
    private String messagesToLogString(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            sb.append("[").append(msg.getOrDefault("role", "?")).append("] ");
            if (msg.containsKey("content")) {
                String content = String.valueOf(msg.get("content"));
                sb.append(content.length() > 120 ? content.substring(0, 120) + "..." : content);
            }
            if (msg.containsKey("tool_calls")) {
                sb.append(" tool_calls: ").append(msg.get("tool_calls"));
            }
            if (msg.containsKey("tool_call_id")) {
                sb.append(" tool_call_id: ").append(msg.get("tool_call_id"));
            }
            sb.append(" | ");
        }
        return sb.toString();
    }
}
