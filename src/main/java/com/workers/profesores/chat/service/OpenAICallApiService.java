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
import java.net.http.HttpClient;
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
            @SuppressWarnings("unchecked") var queryDetails = (List<Map<String,Object>>) ep.getOrDefault("queryDetails", List.of());
            @SuppressWarnings("unchecked") var body       = (List<Object>) ep.getOrDefault("body", List.of());
            sb.append("- **").append(name).append("** — ").append(desc.isBlank() ? "(sin descripción)" : desc).append("\n")
              .append("  - `").append(method).append(" ").append(path).append("`\n");
            if (!pathParams.isEmpty()) sb.append("  - pathParams: ").append(join(pathParams)).append("\n");
            if (!query.isEmpty()) {
                sb.append("  - query: ").append(join(query)).append("\n");
                // include details
                for (Map<String,Object> qd : queryDetails) {
                    try {
                        sb.append("    - ").append(qd.getOrDefault("name","?"));
                        if (qd.containsKey("type")) sb.append(" (type=").append(qd.get("type")).append(")");
                        if (qd.containsKey("default")) sb.append(" default=").append(qd.get("default"));
                        if (qd.containsKey("minimum")) sb.append(" min=").append(qd.get("minimum"));
                        if (qd.containsKey("maximum")) sb.append(" max=").append(qd.get("maximum"));
                        if (qd.containsKey("description")) sb.append(" — ").append(qd.get("description"));
                        sb.append("\n");
                    } catch (Exception e) { /* ignore */ }
                }
            }
            if (!body.isEmpty())       sb.append("  - body: ").append(join(body)).append("\n");
            if (ep.containsKey("paginated") && Boolean.TRUE.equals(ep.get("paginated"))) {
                sb.append("  - nota: respuesta paginada (usa parámetros page/size).\n");
            }
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
    @Deprecated
    public String renderWhitelistTable() {
        // Legacy name - delegate to the spec-first renderer
        return renderEndpointsTable();
    }

    /**
     * New API: render endpoints table (spec-first). Use this method in new code.
     */
    public String renderEndpointsTable() {
        // For safety, return a short narrative description (no Markdown tables)
        return renderWhitelistNarrativeWithDescriptions(getWhitelistLimited());
    }

    /**
     * Return the current endpoints loaded (from served-openapi.json when available).
     * Kept as public helper for other services/tests.
     */
    public List<Map<String, Object>> getEndpoints() {
        return whitelist == null ? List.of() : whitelist;
    }
    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${openai.api.baseurl:https://api.openai.com/v1}")
    private String openaiApiBaseUrl;

    @Value("${openai.api.endpoint.completions:/chat/completions}")
    private String openaiApiEndpointCompletions;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String openaiApiModel;
    @Value("${openai.api.temperature:0.2}")
    private double openaiApiTemperature;
    @Value("${openai.mock:false}")
    private boolean openaiMock;
    private List<Map<String, Object>> whitelist;

    @SuppressWarnings("unused")
    private SpecLoaderService specLoaderService;

    public OpenAICallApiService() {
        // Do not attempt to load legacy YAML at construction time; prefer SpecLoaderService injection.
        this.whitelist = new ArrayList<>();
    }

    /**
     * Test-friendly constructor: accept an InputStream containing a minimal YAML whitelist.
     * This keeps existing unit tests working without reintroducing the legacy YAML loading at
     * application startup.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public OpenAICallApiService(Object unused, InputStream is) {
        this();
        if (is == null) return;
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = yamlMapper.readValue(is, new TypeReference<Map<String, Object>>(){});
            Object endpointsObj = root.get("endpoints");
            if (endpointsObj instanceof List<?>) {
                List<Map<String,Object>> list = new ArrayList<>();
                for (Object item : (List) endpointsObj) {
                    if (item instanceof Map) list.add((Map<String,Object>) item);
                    else {
                        try { list.add(yamlMapper.convertValue(item, new TypeReference<Map<String,Object>>(){})); } catch (Exception e) { /* ignore */ }
                    }
                }
                this.whitelist = list;
            } else {
                this.whitelist = List.of();
            }
            logger.info("Loaded whitelist from test InputStream with {} endpoints", this.whitelist.size());
        } catch (Exception e) {
            logger.warn("Error loading yaml whitelist in test constructor: {}", e.getMessage());
            this.whitelist = List.of();
        }
    }

    // Se usa setter-injection perezosa para evitar referencia circular durante el arranque
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApiProxyService(@Lazy ApiProxyService apiProxyService) {
        this.apiProxyService = apiProxyService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpecLoaderService(SpecLoaderService specLoaderService) {
        this.specLoaderService = specLoaderService;
        // Load whitelist from spec; if missing or empty, fail fast: served-openapi.json is required
        List<Map<String,Object>> specList = specLoaderService.getWhitelist();
        if (specList == null || specList.isEmpty()) {
            throw new RuntimeException("served-openapi.json not found or contains no endpoints. Whitelist is required.");
        }
        this.whitelist = specList;
        logger.info("Loaded whitelist from served-openapi.json with {} endpoints", specList.size());
    }
    // Note: legacy YAML fallback removed - served-openapi.json is the canonical source and required.

    // Definir el campo mapper como un atributo de clase
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Crea el JSON Schema para enforcing structured outputs desde OpenAI.
     * 
     * IMPORTANTE: Usamos "strict": false porque necesitamos flexibilidad.
     * El modo strict=true de OpenAI requiere que:
     * - additionalProperties DEBE ser false
     * - Todas las propiedades deben estar definidas explícitamente
     * - No se pueden tener propiedades opcionales condicionales
     * 
     * Como nuestras respuestas tienen estructura dinámica (usuarios, academias, etc.),
     * usamos strict=false pero seguimos teniendo validación básica de JSON.
     * 
     * Esto garantiza que el LLM:
     * 1. Siempre devuelve JSON válido (no texto natural)
     * 2. Siempre incluye el campo 'text' (obligatorio)
     * 3. Puede incluir cualquier otra propiedad (ui_suggestions, usuarios, etc.)
     */
    private Map<String, Object> buildJsonSchemaResponseFormat() {
        Map<String, Object> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_schema");
        
        Map<String, Object> jsonSchema = new HashMap<>();
        jsonSchema.put("name", "chat_response");
        jsonSchema.put("strict", false); // Cambiado a false para permitir flexibilidad
        
        // Schema principal
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        // Propiedades del objeto raíz
        Map<String, Object> properties = new HashMap<>();
        
        // Propiedad 'text' (obligatoria)
        Map<String, Object> textProp = new HashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "Mensaje de respuesta breve y natural para el usuario (OBLIGATORIO, nunca vacío)");
        properties.put("text", textProp);
        
        schema.put("properties", properties);
        schema.put("required", List.of("text")); // Solo 'text' es obligatorio
        
        jsonSchema.put("schema", schema);
        responseFormat.put("json_schema", jsonSchema);
        
        return responseFormat;
    }

    // Refactorización del método callChatWithTools
    public String callChatWithTools(List<Map<String, Object>> messages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        if (debug) {
            logger.debug("[OpenAICallApiService] callChatWithTools called messagesCount={} authorizationPresent={}", messages == null ? 0 : messages.size(), authorization != null);
        }

        // Cargar la whitelist desde served-openapi.json
        loadWhitelistFromOpenApi();

    // Crear herramientas call_api y call_api_batch
    Map<String, Object> callApiTool = createCallApiTool();
    Map<String, Object> callApiBatchTool = createCallApiBatchTool();

        // Procesar mensajes iterativamente
    return processMessages(messages, List.of(callApiTool, callApiBatchTool), xmlLogger, authorization);
    }

    // Variant: allow passing extra request body properties (e.g., response_format).
    public String callChatWithToolsWithExtras(List<Map<String, Object>> messages, Map<String,Object> extraBodyProps, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        if (debug) {
            logger.debug("[OpenAICallApiService] callChatWithToolsWithExtras called messagesCount={} extrasKeys={} authorizationPresent={}", messages == null ? 0 : messages.size(), (extraBodyProps==null?0:extraBodyProps.keySet()), authorization != null);
        }
        loadWhitelistFromOpenApi();
    Map<String, Object> callApiTool = createCallApiTool();
    Map<String, Object> callApiBatchTool = createCallApiBatchTool();
    return processMessagesWithExtras(messages, List.of(callApiTool, callApiBatchTool), extraBodyProps, xmlLogger, authorization);
    }

    // Reformat-only variant: do NOT register tools or whitelist; force tool_choice="none" and return immediately.
    public String callChatNoToolsWithExtras(List<Map<String, Object>> messages, Map<String,Object> extraBodyProps, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        if (debug) {
            logger.debug("[OpenAICallApiService] callChatNoToolsWithExtras called messagesCount={} extrasKeys={} authorizationPresent={}", messages == null ? 0 : messages.size(), (extraBodyProps==null?0:extraBodyProps.keySet()), authorization != null);
        }
        // Build extras for a NO-TOOLS call. IMPORTANT: Do NOT include 'tool_choice' at all when there are no tools,
        // because OpenAI rejects requests that specify tool_choice without tools.
        Map<String,Object> extras = new HashMap<>();
        if (extraBodyProps != null) {
            extras.putAll(extraBodyProps);
            // Remove any accidental tool_choice/tools hints from caller
            extras.remove("tool_choice");
            extras.remove("tools");
        }
        // Apply a conservative cap of max_tokens to avoid long generations in the second turn
        extras.putIfAbsent("max_tokens", 400);
        Map<String, Object> requestBody = buildRequestBodyNoTools(messages, extras);
        if (xmlLogger != null) {
            xmlLogger.addStep("OpenAI", "Llamada a OpenAI (sin herramientas) - mensajes: " + messagesToLogString(messages));
        }
        return sendRequestToOpenAi(requestBody, authorization);
    }

    // Planner support eliminado: el orquestador funciona en dos turnos sin fase de planificación separada.

    private void loadWhitelistFromOpenApi() {
        try {
            InputStream is = new ClassPathResource("served-openapi.json").getInputStream();
            Map<String, Object> openApiSpec = mapper.readValue(is, new TypeReference<>() {});
            List<Map<String, Object>> paths = new ArrayList<>();
            Object pathsObj = openApiSpec.get("paths");

            // OpenAPI `paths` suele ser un map: { "/ruta": { "get": { ... }, "post": { ... } } }
            if (pathsObj instanceof Map<?, ?>) {
                Map<?, ?> pathsMap = (Map<?, ?>) pathsObj;
                for (Map.Entry<?, ?> pathEntry : pathsMap.entrySet()) {
                    String path = String.valueOf(pathEntry.getKey());
                    Object methodsObj = pathEntry.getValue();
                    if (methodsObj instanceof Map<?, ?>) {
                        Map<?, ?> methodsMap = (Map<?, ?>) methodsObj;
                        for (Map.Entry<?, ?> methodEntry : methodsMap.entrySet()) {
                            String method = String.valueOf(methodEntry.getKey()).toUpperCase();
                            Object opObj = methodEntry.getValue();
                            if (opObj instanceof Map<?, ?>) {
                                Map<?, ?> opMap = (Map<?, ?>) opObj;
                                Map<String, Object> ep = new HashMap<>();
                                Object operationId = opMap.get("operationId");
                                ep.put("operationId", operationId == null ? null : String.valueOf(operationId));
                                ep.put("name", operationId == null ? method + " " + path : String.valueOf(operationId));
                                ep.put("method", method);
                                ep.put("path", path);
                                Object descObj = opMap.get("description");
                                ep.put("description", descObj == null ? "" : String.valueOf(descObj));
                                // Extract query param names and detect pagination by presence of page/size or response schema hints
                                List<String> queryParams = new ArrayList<>();
                                boolean paginated = false;
                                Object parametersObj = opMap.get("parameters");
                                if (parametersObj instanceof List<?>) {
                                    for (Object po : (List<?>) parametersObj) {
                                        if (po instanceof Map<?, ?>) {
                                            Map<?, ?> p = (Map<?, ?>) po;
                                            try {
                                                Object in = p.get("in");
                                                Object name = p.get("name");
                                                if (in != null && "query".equals(String.valueOf(in)) && name != null) {
                                                    String qn = String.valueOf(name);
                                                    queryParams.add(qn);
                                                    if ("page".equals(qn) || "size".equals(qn)) paginated = true;
                                                }
                                            } catch (Exception ignore) { }
                                        }
                                    }
                                }
                                // Detect pagination from 200 response schema properties
                                try {
                                    Object responsesObj = opMap.get("responses");
                                    if (responsesObj instanceof Map<?, ?>) {
                                        Object r200 = ((Map<?, ?>) responsesObj).get("200");
                                        if (r200 instanceof Map<?, ?>) {
                                            Object content = ((Map<?, ?>) r200).get("content");
                                            if (content instanceof Map<?, ?>) {
                                                Object appJson = ((Map<?, ?>) content).get("application/json");
                                                if (appJson instanceof Map<?, ?>) {
                                                    Object schema = ((Map<?, ?>) appJson).get("schema");
                                                    if (schema instanceof Map<?, ?>) {
                                                        Map<?, ?> schemaMap = (Map<?, ?>) schema;
                                                        Object propsObj = schemaMap.get("properties");
                                                        if (propsObj instanceof Map<?, ?>) {
                                                            Map<?, ?> propsMap = (Map<?, ?>) propsObj;
                                                            if (propsMap.containsKey("page") || propsMap.containsKey("size") || propsMap.containsKey("totalElements") || propsMap.containsKey("items")) {
                                                                paginated = true;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignore) { }
                                if (!queryParams.isEmpty()) ep.put("query", queryParams);
                                if (paginated) ep.put("paginated", true);
                                paths.add(ep);
                            }
                        }
                    }
                }

            } else if (pathsObj instanceof List<?>) {
                // Fallback: si paths viene como lista (formato antiguo), convertir elementos
                for (Object item : (List<?>) pathsObj) {
                    if (item instanceof Map<?, ?>) {
                        try {
                            paths.add(mapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                        } catch (IllegalArgumentException e) {
                            logger.warn("Error al convertir el objeto: {}", item, e);
                        }
                    } else {
                        logger.warn("El objeto no es del tipo esperado: {}", item);
                    }
                }
            } else {
                logger.warn("El objeto paths no es del tipo esperado: {}", pathsObj);
            }

            this.whitelist = paths;
            logger.info("Whitelist cargada desde served-openapi.json con {} endpoints", paths.size());
        } catch (Exception e) {
            logger.error("Error al cargar la whitelist desde served-openapi.json: {}", e.getMessage());
            this.whitelist = List.of();
        }
    }

    // Apply default page/size and cap size for paginated GET endpoints
    private JsonNode sanitizePagination(Map<String, Object> endpoint, String method, JsonNode query) {
        try {
            if (endpoint == null) return query;
            Object pag = endpoint.get("paginated");
            boolean isPaginated = (pag instanceof Boolean) ? (Boolean) pag : false;
            if (!isPaginated) return query;
            if (method == null || !"GET".equalsIgnoreCase(method)) return query;
            com.fasterxml.jackson.databind.node.ObjectNode q = (query == null || query.isNull()) ? mapper.createObjectNode() : (query.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : mapper.createObjectNode());
            // default page=1 if missing or invalid
            int page = 1;
            try { if (q.has("page") && q.get("page").canConvertToInt()) page = Math.max(1, q.get("page").asInt()); } catch (Exception ignore) { }
            q.put("page", page);
            // default size=50 if missing; cap to 50 (align with ChatService)
            int size = 50;
            try {
                if (q.has("size") && q.get("size").canConvertToInt()) size = q.get("size").asInt();
            } catch (Exception ignore) { }
            if (size <= 0) size = 50;
            if (size > 50) size = 50;
            q.put("size", size);
            return q;
        } catch (Exception e) {
            // on any error, return original query untouched
            return query;
        }
    }

    private Map<String, Object> createCallApiTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "call_api",
                "description", "Llama a un endpoint permitido de la API de la academia.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Nombre lógico del endpoint (whitelist)"),
                        "method", Map.of("type", "string", "enum", List.of("GET", "POST", "PUT", "DELETE")),
                        "pathParams", Map.of("type", "object"),
                        "query", Map.of("type", "object"),
                        "body", Map.of("type", "object")
                    ),
                    "required", List.of("name", "method")
                )
            )
        );
    }

    private String processMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
        int maxIterations = 10;

        for (int iter = 0; iter < maxIterations; iter++) {
            if (xmlLogger != null) {
                xmlLogger.addStep("OpenAI", "Llamada a OpenAI (iteración " + iter + ") - mensajes: " + messagesToLogString(currentMessages));
            }

            Map<String, Object> requestBody = buildRequestBody(currentMessages, tools);
            String responseBody = sendRequestToOpenAi(requestBody, authorization);

            if (responseBody == null || responseBody.isBlank()) {
                logger.error("Respuesta vacía o nula de OpenAI");
                return mapper.writeValueAsString(Map.of("error", "openai_empty_response", "message", "Empty or null response body"));
            }

            Map<String, Object> response = mapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
            // Validar y convertir de forma segura en processMessages
            Object messageObj = response.get("message");
            if (messageObj instanceof Map<?, ?>) {
                try {
                    currentMessages.add(mapper.convertValue(messageObj, new TypeReference<Map<String, Object>>() {}));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error al convertir el mensaje: {}", messageObj, e);
                }
            } else {
                logger.warn("El mensaje recibido no es del tipo esperado: {}", messageObj);
            }

            if (processToolCalls(response, currentMessages, xmlLogger, authorization)) {
                continue;
            }

            return responseBody;
        }

        return mapper.writeValueAsString(Map.of("error", "too_many_iterations", "message", "Demasiadas iteraciones de tool calling (posible bucle infinito)"));
    }

    private String processMessagesWithExtras(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Map<String,Object> extraBodyProps, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
        int maxIterations = 10;

        for (int iter = 0; iter < maxIterations; iter++) {
            if (xmlLogger != null) {
                xmlLogger.addStep("OpenAI", "Llamada a OpenAI (iteración " + iter + ") - mensajes: " + messagesToLogString(currentMessages));
            }

            Map<String, Object> requestBody = buildRequestBody(currentMessages, tools, extraBodyProps);
            String responseBody = sendRequestToOpenAi(requestBody, authorization);

            if (responseBody == null || responseBody.isBlank()) {
                logger.error("Respuesta vacía o nula de OpenAI");
                return mapper.writeValueAsString(Map.of("error", "openai_empty_response", "message", "Empty or null response body"));
            }

            Map<String, Object> response = mapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
            Object messageObj = response.get("message");
            if (messageObj instanceof Map<?, ?>) {
                try {
                    currentMessages.add(mapper.convertValue(messageObj, new TypeReference<Map<String, Object>>() {}));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error al convertir el mensaje: {}", messageObj, e);
                }
            } else {
                logger.warn("El mensaje recibido no es del tipo esperado: {}", messageObj);
            }

            if (processToolCalls(response, currentMessages, xmlLogger, authorization)) {
                continue;
            }

            return responseBody;
        }

        return mapper.writeValueAsString(Map.of("error", "too_many_iterations", "message", "Demasiadas iteraciones de tool calling (posible bucle infinito)"));
    }

    private Map<String, Object> buildRequestBody(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String,Object> body = new HashMap<>();
        body.put("model", openaiApiModel);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("temperature", openaiApiTemperature);
        // AÑADIR JSON Schema enforcement para structured outputs
        body.put("response_format", buildJsonSchemaResponseFormat());
        return body;
    }

    private Map<String, Object> buildRequestBody(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Map<String,Object> extras) {
        Map<String,Object> body = new HashMap<>();
        body.put("model", openaiApiModel);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("temperature", openaiApiTemperature);
        // AÑADIR JSON Schema enforcement si no viene explícito en extras
        if (extras == null || !extras.containsKey("response_format")) {
            body.put("response_format", buildJsonSchemaResponseFormat());
        }
        if (extras != null) {
            body.putAll(extras);
        }
        return body;
    }

    // Build a request body without tools, for reformat-only second turn
    private Map<String, Object> buildRequestBodyNoTools(List<Map<String, Object>> messages, Map<String,Object> extras) {
        Map<String,Object> body = new HashMap<>();
        body.put("model", openaiApiModel);
        body.put("messages", messages);
        body.put("temperature", openaiApiTemperature);
        // AÑADIR JSON Schema enforcement si no viene explícito en extras
        if (extras == null || !extras.containsKey("response_format")) {
            body.put("response_format", buildJsonSchemaResponseFormat());
        }
        if (extras != null) {
            body.putAll(extras);
        }
        return body;
    }

    private String sendRequestToOpenAi(Map<String, Object> requestBody, String authorization) {
        try {
            RestTemplate restTemplate = createRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            String url = composeCompletionsUrl();
            if (debug) logger.debug("[OpenAICallApiService] Calling OpenAI URL={} model={} messagesCount={}", url, openaiApiModel, ((List<?>) requestBody.getOrDefault("messages", List.of())).size());
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            int status = response.getStatusCode() == null ? -1 : response.getStatusCode().value();
            String body = response.getBody();
            String snippet = body == null ? "" : (body.length() > 300 ? body.substring(0,300) + "..." : body);
            if (debug) logger.debug("[OpenAICallApiService] OpenAI response status={} bodySnippet={}", status, snippet);
            return body;
        } catch (Exception e) {
            logger.error("Error al llamar a OpenAI: {}", e.getMessage());
            return null;
        }
    }

    private boolean processToolCalls(Map<String, Object> response, List<Map<String, Object>> currentMessages, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) {
        Object toolCallsObj = response.get("tool_calls");
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (toolCallsObj instanceof List<?>) {
            for (Object item : (List<?>) toolCallsObj) {
                if (item instanceof Map<?, ?>) {
                    try {
                        toolCalls.add(mapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                    } catch (IllegalArgumentException e) {
                        logger.warn("Error al convertir tool_call: {}", item, e);
                    }
                }
            }
        } else {
            if (toolCallsObj != null) logger.warn("El objeto tool_calls no es del tipo esperado: {}", toolCallsObj);
        }

        // Si no hay tool calls, no hay motivo para volver a llamar a OpenAI
        if (toolCalls.isEmpty()) {
            if (debug) logger.info("No hubo tool_calls en la respuesta.");
            return false;
        }

        boolean anyExecuted = false;
        for (Map<String, Object> toolCall : toolCalls) {
            String toolResult = executeToolCall(toolCall, authorization, xmlLogger);
            if (toolResult != null) {
                currentMessages.add(Map.of("role", "tool", "content", toolResult));
                anyExecuted = true;
            }
        }

        return anyExecuted;
    }

    // New signature used internally (accepts xmlLogger). Keep a backward-compatible wrapper for tests.
    private String executeToolCall(Map<String, Object> toolCall, String authorization, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) {
        String toolName = (String) toolCall.get("name");
        if (!"call_api".equals(toolName) && !"call_api_batch".equals(toolName)) {
            return "{\"error\":\"tool_not_supported\",\"message\":\"Tool no soportada: " + toolName + "\"}";
        }

        if ("call_api_batch".equals(toolName)) {
            return executeToolCallBatch(toolCall, authorization, xmlLogger);
        }

        Object argumentsObj = toolCall.get("arguments");
        Map<String, Object> args;
        if (argumentsObj instanceof Map<?, ?>) {
            try {
                args = mapper.convertValue(argumentsObj, new TypeReference<Map<String, Object>>() {});
                logger.info("Arguments procesados correctamente: {}", args);
            } catch (IllegalArgumentException e) {
                logger.warn("Error al convertir arguments: {}", argumentsObj, e);
                return "{\"error\":\"bad_arguments\",\"message\":\"Invalid arguments\"}";
            }
        } else {
            logger.warn("El objeto arguments no es del tipo esperado: {}", argumentsObj);
            return "{\"error\":\"bad_arguments\",\"message\":\"Invalid arguments\"}";
        }

        String endpointName = args.get("name") == null ? null : String.valueOf(args.get("name"));
        if (endpointName == null) {
            return "{\"error\":\"missing_argument\",\"message\":\"Missing argument 'name'\"}";
        }

        Map<String, Object> endpoint = getEndpointByName(endpointName);
        if (endpoint == null) {
            return "{\"error\":\"endpoint_not_allowed\",\"message\":\"Endpoint no permitido: " + endpointName + "\"}";
        }

        try {
            JsonNode pathParams = mapper.valueToTree(args.getOrDefault("pathParams", Collections.emptyMap()));
            JsonNode query = mapper.valueToTree(args.getOrDefault("query", Collections.emptyMap()));
            JsonNode body = mapper.valueToTree(args.getOrDefault("body", Collections.emptyMap()));
            String method = args.get("method") == null ? "GET" : String.valueOf(args.get("method"));
            // Enforce conservative pagination defaults/caps for paginated endpoints
            query = sanitizePagination(endpoint, method, query);
            return apiProxyService.executeSpecCall(endpoint, method, pathParams, query, body, authorization, xmlLogger);
        } catch (Exception e) {
            logger.warn("Exception in executeToolCall: {}", e.getMessage(), e);
            return "{\"error\":\"exception\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    // Backwards-compatible method used by some unit tests via reflection
    @SuppressWarnings("unused")
    public String executeToolCall(Map<String, Object> toolCall, String authorization) {
        return executeToolCall(toolCall, authorization, null);
    }
    private String composeCompletionsUrl() {
        String base = openaiApiBaseUrl == null ? "https://api.openai.com/v1" : openaiApiBaseUrl.trim();
        String endpoint = openaiApiEndpointCompletions == null ? "/chat/completions" : openaiApiEndpointCompletions.trim();
        if (!base.endsWith("/") && !endpoint.startsWith("/")) base = base + "/";
        return base.endsWith("/") ? base.replaceAll("/+$", "") + endpoint : base + endpoint;
    }

    private Map<String, Object> createCallApiBatchTool() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", "call_api_batch",
                "description", "Ejecuta varias llamadas a la API en un único tool_call; el modelo proporciona un array de calls.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "calls", Map.of(
                            "type", "array",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "name", Map.of("type", "string"),
                                    "method", Map.of("type", "string", "enum", List.of("GET","POST","PUT","DELETE")),
                                    "pathParams", Map.of("type", "object"),
                                    "query", Map.of("type", "object"),
                                    "body", Map.of("type", "object")
                                ),
                                "required", List.of("name","method")
                            )
                        )
                    ),
                    "required", List.of("calls")
                )
            )
        );
    }

    private String executeToolCallBatch(Map<String, Object> toolCall, String authorization, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) {
        Object argumentsObj = toolCall.get("arguments");
        Map<String, Object> args;
        try {
            args = mapper.convertValue(argumentsObj, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"bad_arguments\",\"message\":\"Invalid arguments for call_api_batch\"}";
        }
        Object callsObj = args.get("calls");
        if (!(callsObj instanceof List<?>)) {
            return "{\"error\":\"bad_arguments\",\"message\":\"'calls' debe ser un array\"}";
        }
        List<?> calls = (List<?>) callsObj;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object c : calls) {
            if (!(c instanceof Map<?, ?>)) continue;
            Map<String, Object> call = mapper.convertValue(c, new TypeReference<Map<String, Object>>() {});
            String single = executeToolCall(Map.of(
                "name", "call_api",
                "arguments", call
            ), authorization, xmlLogger);
            // wrap each result as { ok: true/false, result: <json or error> }
            Map<String, Object> wrapped = new HashMap<>();
            try {
                JsonNode node = mapper.readTree(single);
                wrapped.put("ok", !(node.has("error") || (node.has("ok") && !node.path("ok").asBoolean(true))));
                wrapped.put("result", node);
            } catch (Exception e) {
                wrapped.put("ok", false);
                wrapped.put("result", single);
            }
            results.add(wrapped);
        }
        try {
            return mapper.writeValueAsString(Map.of("ok", true, "results", results));
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"batch_serialize_failed\"}";
        }
    }

    // Build response_format extras enforcing a JSON schema for chat responses: requires non-empty 'text'.
    public Map<String,Object> buildChatResponseSchemaExtras() {
        Map<String,Object> textSchema = new HashMap<>();
        textSchema.put("type", "string");
        textSchema.put("minLength", 1);

        Map<String,Object> sugItemProps = new HashMap<>();
        sugItemProps.put("id", Map.of("type","string"));
        sugItemProps.put("display_text", Map.of("type","string","minLength",1));
        sugItemProps.put("type", Map.of("type","string","enum", List.of("Paginacion","Registro","Generica")));
        sugItemProps.put("recordAction", Map.of("type","string","enum", List.of("Alta","Baja","Modificacion","Consulta")));
        Map<String,Object> sugItem = new HashMap<>();
        sugItem.put("type","object");
        sugItem.put("properties", sugItemProps);
        sugItem.put("required", List.of("id","display_text","type"));
        sugItem.put("additionalProperties", true);

        Map<String,Object> properties = new HashMap<>();
        properties.put("text", textSchema);
        properties.put("ui_suggestions", Map.of("type","array","items", sugItem));
        properties.put("summary_fields", Map.of("type","array","items", Map.of("type","string")));

        Map<String,Object> schema = new HashMap<>();
        schema.put("type","object");
        schema.put("properties", properties);
        schema.put("required", List.of("text"));
        schema.put("additionalProperties", true);

        Map<String,Object> jsonSchemaWrapper = new HashMap<>();
        jsonSchemaWrapper.put("name", "chat_response");
        jsonSchemaWrapper.put("schema", schema);

        Map<String,Object> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchemaWrapper);

        return Map.of("response_format", responseFormat);
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

    // Configurar el cliente HTTP para manejar redireccionamientos automáticamente
    HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();

    // Usar este cliente para todas las solicitudes HTTP

    // Implementar el método getEndpointByName (package-private para tests)
    Map<String, Object> getEndpointByName(String name) {
        if (whitelist == null) return null;
        for (Map<String, Object> endpoint : whitelist) {
            Object operationId = endpoint.get("operationId");
            if (operationId != null && operationId.equals(name)) {
                return endpoint;
            }
        }
        return null;
    }
}
