
package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import java.io.InputStream;
import java.util.*;

@Service
public class OpenAICallApiService {
    private ApiProxyService apiProxyService;
    private static final Logger logger = LoggerFactory.getLogger(OpenAICallApiService.class);
    @Value("${backend.debug:false}")
    private boolean debug;
    // Permite mockear el RestTemplate en tests
    protected RestTemplate createRestTemplate() { return new RestTemplate(); }
    // --- Configuración de renderizado de whitelist ---
    @Value("${whitelist.render.table:false}")
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
        // For safety, return a short narrative description (no Markdown tables)
        return renderWhitelistNarrativeWithDescriptions(getWhitelistLimited());
    }
    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${openai.api.baseurl:https://api.openai.com/v1}")
    private String openaiApiBaseUrl;

    @Value("${openai.api.endpoint.completions:/chat/completions}")
    private String openaiApiEndpointCompletions;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String openaiApiModel;
    @Value("${openai.mock:false}")
    private boolean openaiMock;
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
            logger.warn("No se pudo cargar la whitelist: {}", e.getMessage());
        }
    }

    // Se usa setter-injection perezosa para evitar referencia circular durante el arranque
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApiProxyService(@Lazy ApiProxyService apiProxyService) {
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
            logger.warn("No se pudo cargar la whitelist: {}", e.getMessage());
        }
    }

    public String callChatWithTools(List<Map<String, Object>> messages) throws Exception {
        return callChatWithTools(messages, null, null);
    }

    public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) throws Exception {
        return callChatWithTools(messages, xmlLogger, null);
    }

    // Nueva sobrecarga que acepta Authorization header para propagarlo a las tool calls que hagan llamadas a la API
    public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        if (debug) {
            System.out.println("[OpenAICallApiService][DEBUG] callChatWithTools llamado con mensajes: " + messages + ", authorization=" + (authorization != null ? "present" : "absent"));
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
            requestBody.put("model", openaiApiModel);
            requestBody.put("messages", currentMessages);
            requestBody.put("tools", List.of(callApiTool));
            if (debug) {
                System.out.println("[OpenAICallApiService][DEBUG] Iteración " + iter + " - Payload enviado a OpenAI: " + requestBody);
            }
            String responseBody;
            if (openaiMock) {
                // Build a deterministic mock response that contains a single tool_call to call_api
                if (xmlLogger != null) xmlLogger.addStep("OpenAI", "openai.mock=true -> devolviendo respuesta simulada (tool_call listAcademias)");
                Map<String,Object> function = new HashMap<>();
                function.put("name", "call_api");
                // arguments must be a JSON string as the real OpenAI responses often encode them as string
                Map<String,Object> args = new HashMap<>();
                args.put("name", "listAcademias");
                args.put("method", "GET");
                args.put("pathParams", Map.of());
                args.put("query", Map.of());
                args.put("body", Map.of());
                function.put("arguments", mapper.writeValueAsString(args));
                Map<String,Object> toolCall = Map.of("id", "mock-1", "function", function);
                Map<String,Object> message = Map.of("role", "assistant", "tool_calls", List.of(toolCall));
                Map<String,Object> choice = Map.of("message", message);
                Map<String,Object> respMap = Map.of("choices", List.of(choice));
                responseBody = mapper.writeValueAsString(respMap);
                if (debug) System.out.println("[OpenAICallApiService][DEBUG] Mock OpenAI response: " + responseBody);
            } else {
                try {
                    RestTemplate restTemplate = createRestTemplate();
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Authorization", "Bearer " + openaiApiKey);
                    headers.set("Content-Type", "application/json");
                    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
                    String url = composeCompletionsUrl();
                    ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                    if (response == null) {
                        logger.error("La llamada a OpenAI devolvió ResponseEntity nula");
                        responseBody = mapper.writeValueAsString(Map.of("error", "openai_response_null", "message", "ResponseEntity is null"));
                    } else {
                        responseBody = response.getBody();
                    }
                } catch (Exception ex) {
                    logger.error("Error al llamar a OpenAI: {}", ex.getMessage());
                    // Nunca lanzar: siempre devolver un body JSON para que el consumidor pueda parsearlo
                    responseBody = mapper.writeValueAsString(Map.of("error", "openai_request_failed", "message", ex.getMessage() == null ? "" : ex.getMessage()));
                }
                if (debug) {
                    System.out.println("[OpenAICallApiService][DEBUG] Respuesta recibida de OpenAI: " + responseBody);
                }
                if (xmlLogger != null) xmlLogger.addStep("OpenAI", "Respuesta de OpenAI: " + responseBody);
                if (responseBody == null || responseBody.isBlank()) {
                    logger.error("La llamada a OpenAI devolvió un body nulo o vacío, generando objeto de error");
                    responseBody = mapper.writeValueAsString(Map.of("error", "openai_empty_response", "message", "Empty or null response body"));
                }
            }
            Map<String, Object> resp = mapper.readValue(responseBody, new TypeReference<Map<String, Object>>(){});
            // Procesar la respuesta de OpenAI usando conversiones seguras
            List<Map<String, Object>> choices = mapper.convertValue(resp.get("choices"), new TypeReference<List<Map<String, Object>>>(){});
            if (choices == null || choices.isEmpty()) {
                return responseBody; // Respuesta inesperada
            }
            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = mapper.convertValue(choice.get("message"), new TypeReference<Map<String, Object>>(){});
            // Si hay tool_calls, procesarlas
            if (message.containsKey("tool_calls")) {
                List<Map<String, Object>> toolCallsRaw = mapper.convertValue(message.get("tool_calls"), new TypeReference<List<Map<String, Object>>>(){});
                if (toolCallsRaw == null) {
                    return responseBody; // Respuesta inesperada
                }
                // Insertar los mensajes tool justo después del assistant con tool_calls
                currentMessages.add(message); // Añadir el mensaje assistant con tool_calls
                List<Map<String, Object>> toolMsgs = new ArrayList<>();
                for (Map<String, Object> toolCall : toolCallsRaw) {
                    Map<String, Object> function = mapper.convertValue(toolCall.get("function"), new TypeReference<Map<String, Object>>(){});
                    if (function == null) continue;
                    String toolName = (String) function.get("name");
                    String toolId = toolCall.get("id") == null ? null : String.valueOf(toolCall.get("id"));
                    String argumentsJson = function.get("arguments") == null ? "{}" : String.valueOf(function.get("arguments"));
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
                            Map<String,Object> err = Map.of("error", "endpoint_not_allowed", "message", "Endpoint no permitido o no encontrado: " + endpointName);
                            toolResult = mapper.writeValueAsString(err);
                            if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Endpoint no permitido o no encontrado: " + endpointName);
                        } else {
                            try {
                                // use the string-returning executeWhitelistedCall which normalizes errors into JSON
                                toolResult = apiProxyService.executeWhitelistedCall(endpoint, methodFromModel, pathParams, query, body, authorization);
                                if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Respuesta de API (string)");
                            } catch (Exception ex2) {
                                // Ensure we always return a JSON object describing the failure
                                Map<String,Object> err = Map.of("error", "exception", "message", ex2.getMessage() == null ? "" : ex2.getMessage());
                                toolResult = mapper.writeValueAsString(err);
                                if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Excepción al llamar API: " + ex2.getMessage());
                            }
                        }
                    } else {
                        Map<String,Object> err = Map.of("error", "tool_not_supported", "message", "Tool no soportada: " + toolName);
                        toolResult = mapper.writeValueAsString(err);
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
                return responseBody;
            }
        }
            // Si se alcanza este punto, se ha superado el número máximo de iteraciones
            Map<String,Object> errTooMany = Map.of("error", "too_many_iterations", "message", "Demasiadas iteraciones de tool calling (posible bucle infinito)");
            return mapper.writeValueAsString(errTooMany);
    }

    // Método utilitario para buscar endpoint por nombre (restaurado)
    public Map<String, Object> getEndpointByName(String name) {
        if (whitelist == null) return null;
        for (Map<String, Object> ep : whitelist) {
            if (Objects.equals(ep.get("name"), name)) return ep;
        }
        return null;
    }
    private String composeCompletionsUrl() {
        String base = openaiApiBaseUrl == null ? "https://api.openai.com/v1" : openaiApiBaseUrl.trim();
        String endpoint = openaiApiEndpointCompletions == null ? "/chat/completions" : openaiApiEndpointCompletions.trim();
        if (!base.endsWith("/") && !endpoint.startsWith("/")) base = base + "/";
        return base.endsWith("/") ? base.replaceAll("/+$", "") + endpoint : base + endpoint;
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
