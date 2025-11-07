package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.config.ParametrosArbolDecision2CallOpenAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.workers.profesores.chat.dto.response.*;
import com.workers.profesores.chat.prompt.PromptOpenAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

@Service
public class ChatService {
    private final OpenAICallApiService openai;
    private final ApiProxyService apiProxy;
    private final ObjectMapper om = new ObjectMapper();
    private final com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService;
    private final ParametrosArbolDecision2CallOpenAI parametros;
    private final ContextTokenService contextTokenService;
    private final PromptOpenAi promptBuilder;
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${backend.debug:false}")
    private boolean debug;
    // Modo fastpath eliminado: no hay flag local
    // Modo LITE eliminado: sin flag
    // Note: local welcome handling removed to always delegate to OpenAI

    // Exec metadata to support fast-path without a second OpenAI round-trip
    @SuppressWarnings("unused")
    private static class ExecMeta {
        String endpointName;
        String method;
        JsonNode apiResultNode;
        String raw;
        // Added to preserve filters/sort and endpoint path for pagination context
        JsonNode querySnapshot;
        String endpointPath;
        JsonNode pathParamsSnapshot;
    }

    // Contexto de navegación inferido del historial cuando el cliente no envía Paginacion_token
    private static class NavContext {
        String direction; // "next" | "prev"
        Integer currentPage;
    }

    // Extrae del historial (assistant + user) la intención de navegación y la página actual, p. ej.
    // assistant: "Mostrando página 1 de usuarios" + user: "Siguiente" => direction=next, currentPage=1
    private NavContext extractNavContext(List<ChatRequest.Message> incoming) {
        if (incoming == null || incoming.isEmpty()) return null;
        String lastUser = null;
        Integer pageFromAssistant = null;
        try {
            for (int i = incoming.size() - 1; i >= 0; i--) {
                ChatRequest.Message m = incoming.get(i);
                if (m == null) continue;
                String role = m.getRole() == null ? "" : m.getRole().toLowerCase();
                String content = m.getContent() == null ? "" : m.getContent();
                if (lastUser == null && "user".equals(role)) {
                    lastUser = content.toLowerCase();
                }
                if (pageFromAssistant == null && ("assistant".equals(role) || "system".equals(role))) {
                    // Buscar patrones "Mostrando página N" tolerando tilde
                    String cLower = content.toLowerCase();
                    java.util.regex.Matcher mm = java.util.regex.Pattern
                        .compile("mostrando p[áa]gina\\s+(\\d+)")
                        .matcher(cLower);
                    if (mm.find()) {
                        try { pageFromAssistant = Integer.parseInt(mm.group(1)); } catch (Exception ignore) { }
                    }
                }
            }
            if (lastUser == null || pageFromAssistant == null) return null;
            NavContext nc = new NavContext();
            if (lastUser.contains("siguiente") || lastUser.contains("next")) {
                nc.direction = "next";
                nc.currentPage = pageFromAssistant;
                return nc;
            }
            if (lastUser.contains("anterior") || lastUser.contains("previo") || lastUser.contains("previous")) {
                nc.direction = "prev";
                nc.currentPage = pageFromAssistant;
                return nc;
            }
            return null;
        } catch (Exception ignore) { return null; }
    }

    // Ajusta la query con heurística de navegación si no hay token: establece 'page' si falta
    @SuppressWarnings("unused")
    private JsonNode adjustQueryWithNavContext(Map<String, Object> endpoint, String method, JsonNode query, NavContext nav) {
        try {
            if (endpoint == null || nav == null) return query;
            Object pag = endpoint.get("paginated");
            boolean isPaginated = (pag instanceof Boolean) ? (Boolean) pag : false;
            if (!isPaginated) return query;
            if (method == null || !"GET".equalsIgnoreCase(method)) return query;
            com.fasterxml.jackson.databind.node.ObjectNode q = (query == null || query.isNull())
                ? om.createObjectNode()
                : (query.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode());
            boolean hasPage = q.has("page") && q.get("page").canConvertToInt();
            if (!hasPage && nav.currentPage != null && nav.direction != null) {
                int target = "next".equals(nav.direction) ? (nav.currentPage + 1) : Math.max(1, nav.currentPage - 1);
                q.put("page", target);
            }
            return q;
        } catch (Exception ignore) { return query; }
    }

    // (Eliminado) No se realiza detección de saludo en backend: la IA decide cómo responder

    // Si el segundo turno normal devolvió solo {text,...} sin arrays de recursos, añadimos los items/paginación reales del tool_call ejecutado
    private JsonNode maybeEnrichWithExecutedItems(JsonNode modelNode, List<ExecMeta> executed) {
        try {
            if (modelNode == null || !modelNode.isObject()) return modelNode;
            if (executed == null || executed.size() != 1) return modelNode;
            ExecMeta meta = executed.get(0);
            ItemsAndPagination ip = findItemsArray(meta.apiResultNode, extractTargetFromEndpointName(meta.endpointName));
            if (ip == null || ip.items == null) return modelNode;
            // ¿El modelo ya devolvió algún array conocido? Si sí, no tocamos.
            boolean hasKnownArray = false;
            for (String k : parametros.getAllowedTargetsPlural()) {
                JsonNode arr = modelNode.get(k);
                if (arr != null && arr.isArray()) { hasKnownArray = true; break; }
            }
            if (hasKnownArray) return modelNode;
            // Clonamos el objeto y metemos items bajo una clave inferida
            com.fasterxml.jackson.databind.node.ObjectNode out = modelNode.deepCopy();
            String target = extractTargetFromEndpointName(meta.endpointName);
            if (target == null) target = guessTargetFromItems(ip.items);
            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
            for (JsonNode it : ip.items) arr.add(it);
            if (target != null) out.set(target, arr); else out.set("items", arr);
            if (ip.pagination != null) out.set("pagination", ip.pagination);
            return out;
        } catch (Exception ignore) { return modelNode; }
    }

    @Autowired
    public ChatService(OpenAICallApiService openai,
                       ApiProxyService apiProxy,
                       com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService,
                       ParametrosArbolDecision2CallOpenAI parametros) {
    this(openai, apiProxy, jwtDelegationService, parametros, new ContextTokenService());
    }

    // Convenience constructor for unit tests that don't wire Spring configuration properties
    public ChatService(OpenAICallApiService openai,
                       ApiProxyService apiProxy,
                       com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService) {
    this(openai, apiProxy, jwtDelegationService, new ParametrosArbolDecision2CallOpenAI(), new ContextTokenService());
    }

    

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy, com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService,
                       ParametrosArbolDecision2CallOpenAI parametros,
                       ContextTokenService contextTokenService) {
        this.openai = openai;
        this.apiProxy = apiProxy;
        this.jwtDelegationService = jwtDelegationService;
        this.parametros = parametros == null ? new ParametrosArbolDecision2CallOpenAI() : parametros;
        this.contextTokenService = contextTokenService == null ? new ContextTokenService() : contextTokenService;
        this.promptBuilder = new PromptOpenAi(this.parametros);
    // Modo LITE y fastpath eliminados: sin inicialización de flags
    }


    public ResponseEnvelope runChat(List<ChatRequest.Message> incoming, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization, com.workers.profesores.chat.auth.UserClaims claims) {
        try {
            long t0 = System.currentTimeMillis();
            if (debug) {
                System.out.println("[ChatService][DEBUG] runChat called with incoming: " + incoming);
            }
            if (debug && authorization != null) {
                try {
                    String a = authorization.trim();
                    if (a.length() > 10) a = a.substring(0, 7) + "...";
                    System.out.println("[ChatService][DEBUG] Authorization header received (masked): " + a);
                } catch (Exception ex) { /* ignore */ }
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Inicio de runChat");
            // 0) System prompt base + whitelist dinámica (via PromptOpenAi)
            // Perfil del usuario para personalización: si el JWT trae displayName, no hagas prefetch.
            String profileJsonForPrompt = "{}";
            // Create delegated token up-front so prefetch calls to ApiProxyService use delegated auth
            String delegatedAuthUpfront = null;
            try {
                delegatedAuthUpfront = jwtDelegationService.createDelegatedAuthorizationHeader(claims);
                if (delegatedAuthUpfront != null && xmlLogger != null) {
                    try {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(delegatedAuthUpfront.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                        String preview = delegatedAuthUpfront.length() > 12 ? delegatedAuthUpfront.substring(0, 8) + "..." : delegatedAuthUpfront;
                        xmlLogger.addStep("ChatService", "Delegated token creado: preview=" + preview + ", token_sha4=" + sb.toString());
                    } catch (Exception ignore) { }
                }
                if (debug) logger.debug("[ChatService] delegatedAuthUpfront present={}", delegatedAuthUpfront != null);
            } catch (Exception dex) {
                if (debug) logger.debug("[ChatService] Failed to create delegated token up-front: {}", dex.getMessage());
            }
            boolean hasDisplayName = (claims != null && claims.displayName != null && !claims.displayName.isBlank());
            try {
                // Try canonical friendly name first; if not found try operationId used in the API
                Map<String, Object> epProfile = hasDisplayName ? null : openai.getEndpointByName("getMiPerfil");
                if (epProfile == null) {
                    // The served-openapi often exposes operationId like 'usuarios.obtener_mi_perfil'
                    epProfile = hasDisplayName ? null : openai.getEndpointByName("usuarios.obtener_mi_perfil");
                }
                if (epProfile != null) {
                    if (xmlLogger != null && authorization != null) {
                        try {
                            String a = authorization.trim();
                            String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                            byte[] digest = md.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                                xmlLogger.addStep("ChatService", "Authorization preview=" + preview + ", token_sha4=" + sb.toString());
                        } catch (Exception ignore) { }
                    }
                    // executeWhitelistedCall normaliza a JSON string
                    // Use delegated token for prefetch if available
                    String authForPrefetch = delegatedAuthUpfront != null ? delegatedAuthUpfront : authorization;
                    // Use empty ObjectNodes instead of null to ensure mocks using Mockito's any() matchers
                    com.fasterxml.jackson.databind.JsonNode emptyNode = om.createObjectNode();
                    if (xmlLogger != null) {
                        profileJsonForPrompt = apiProxy.executeSpecCall(epProfile, "GET", emptyNode, emptyNode, emptyNode, authForPrefetch, xmlLogger);
                    } else {
                        profileJsonForPrompt = apiProxy.executeSpecCall(epProfile, "GET", emptyNode, emptyNode, emptyNode, authForPrefetch);
                    }
                    try {
                        // keep profileJsonForPrompt as-is; attempt a quick parse to validate JSON
                        om.readTree(profileJsonForPrompt);
                    } catch (Exception ex) {
                        // ignore parsing errors, keep raw profileJsonForPrompt
                    }
                } else if (hasDisplayName) {
                    // Synthesize a minimal profile JSON from claims to feed the prompt
                    try {
                        com.fasterxml.jackson.databind.node.ObjectNode p = om.createObjectNode();
                        if (claims != null && claims.displayName != null) p.put("nombre", claims.displayName);
                        if (claims != null && claims.usuarioId != null) p.put("id", claims.usuarioId);
                        if (claims != null && claims.academiaId != null) p.put("academia_id", claims.academiaId);
                        profileJsonForPrompt = p.toString();
                    } catch (Exception ignore) { /* keep {} */ }
                }
            } catch (Exception e) {
                if (debug) System.out.println("[ChatService][DEBUG] getMiPerfil failed while building prompt: " + e.getMessage());
            }

            String systemPrompt = promptBuilder.buildSystemPrompt(openai, claims, profileJsonForPrompt);
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "System prompt construido y whitelist añadida");
            if (debug) {
                System.out.println("[ChatService][DEBUG] System prompt constructed (trimmed): " + (systemPrompt.length() > 300 ? systemPrompt.substring(0, 300) + "..." : systemPrompt));
            }

            Map<String, Object> systemMsg = Map.of(
                "role", "system",
                "content", systemPrompt
            );

            // 1) Construimos los mensajes a enviar:
            List<Map<String, Object>> seed = new ArrayList<>();
            seed.add(systemMsg);
            // If we have a profile JSON, add it as a separate system message to make the user's name prominent
            if (profileJsonForPrompt != null && profileJsonForPrompt.trim().length() > 2 && !profileJsonForPrompt.trim().equals("{}")) {
                seed.add(Map.of("role", "system", "content", "Perfil_usuario: " + profileJsonForPrompt));
            }
            // Sin store de paginación: la navegación se hará con ui_suggestions + contextToken
            if (incoming != null) for (ChatRequest.Message m : incoming) {
                String role = m.getRole();
                if ("system".equals(role)) continue;
                String content = m.getContent();
                // Oculta el token de paginación del contexto del LLM para no contaminar el prompt ni pagar tokens extra
                if (content != null) {
                    String marker = "Paginacion_token:";
                    int idx = content.indexOf(marker);
                    if (idx >= 0) {
                        String sanitized = content.substring(0, idx).trim();
                        if (sanitized.isEmpty()) {
                            // Si el mensaje era solo el token, lo omitimos por completo
                            continue;
                        } else {
                            content = sanitized;
                        }
                    }
                }
                seed.add(Map.of(
                    "role", m.getRole(),
                    "content", content
                ));
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Mensajes de usuario preparados para OpenAI");

            // 1.5) Planner desactivado: orquestador puro; el modelo decide herramientas en el primer turno
            if (xmlLogger != null) {
                try {
                    int clientMsgs = incoming == null ? 0 : incoming.size();
                    long seedBytes = 0L;
                    for (Map<String,Object> m : seed) {
                        Object cObj = m.get("content");
                        if (cObj != null) seedBytes += String.valueOf(cObj).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    }
                    xmlLogger.addStep("Telemetry", "client_messages=" + clientMsgs + ", seed_messages=" + seed.size() + ", seed_bytes=" + seedBytes);
                } catch (Exception ignore) { }
            }

            // Removed local welcome shortcut: all messages now go through OpenAI

            if (debug) {
                System.out.println("[ChatService][DEBUG] Seed messages for OpenAI: " + seed);
            }

            // 2) Primer turno al modelo (puede devolver tool_calls)

            if (debug) {
                logger.debug("[ChatService] Calling OpenAI with seed messages (seedSize={})", seed.size());
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (seedSize=" + seed.size() + ")");
            }
            // Telemetry: tiempo de preparación de seed hasta la primera llamada
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "seed_ms=" + (System.currentTimeMillis() - t0));
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (callChatWithTools)");
            // Use delegated token for tool_calls if available, otherwise fall back to original authorization
            String authForToolCalls = delegatedAuthUpfront != null ? delegatedAuthUpfront : authorization;
            String rawFirst = openai.callChatWithTools(seed, xmlLogger, authForToolCalls);
            if (debug) {
                // Log del JSON completo con pretty-print (sin afectar la respuesta HTTP)
                try {
                    JsonNode tmp = om.readTree(rawFirst == null ? "" : rawFirst);
                    String pretty = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tmp);
                    System.out.println("[ChatService][DEBUG] Raw OpenAI first response string (pretty):\n" + pretty);
                } catch (Exception ignore) {
                    System.out.println("[ChatService][DEBUG] Raw OpenAI first response string: " + (rawFirst == null ? "<null>" : rawFirst));
                }
            }
            JsonNode first;
            try {
                first = om.readTree(rawFirst == null ? "" : rawFirst);
            } catch (Exception e) {
                // Si la respuesta es inválida (no-JSON), no exponer el texto bruto al usuario: devolver mensaje vacío
                if (debug) System.out.println("[ChatService][DEBUG] OpenAI first response not JSON; returning empty message for user and logging debug.");
                    return applyFinalFallback(ResponseEnvelope.success("", DataSection.of("chat", List.of(), null), List.of(), List.of(MessageEntry.of("debug","raw_first_not_json"))));
            }
            if (debug) {
                logger.debug("[ChatService] Parsed OpenAI first response into JSON");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "OpenAI primera respuesta parseada a JSON");
            }
            // Telemetría: fin de parseo inicial
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "parse_ms=" + (System.currentTimeMillis() - t0));
            if (debug) logger.debug("[Telemetry] parse_ms={}", (System.currentTimeMillis() - t0));
            // Defensive: ensure choices array exists and has at least one element
            JsonNode choicesNode = first.path("choices");
            if (choicesNode == null || !choicesNode.isArray() || choicesNode.size() == 0) {
                // Si OpenAI devolvió un objeto con 'error', no mostrarlo al usuario; devolver mensaje vacío
                if (first.has("error")) {
                    if (debug) System.out.println("[ChatService][DEBUG] OpenAI first response has error; returning empty message to user.");
                    return applyFinalFallback(ResponseEnvelope.success("", DataSection.of("chat", List.of(), null), List.of(), List.of(MessageEntry.of("debug","openai_first_error"))));
                }
                // Si trae 'text', úsalo; si no, deja vacío (no exponer JSON crudo)
                String msg = first.has("text") ? first.path("text").asText("") : "";
                return applyFinalFallback(ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of()));
            }
            JsonNode choice = choicesNode.get(0);
            JsonNode assistantMsg = (choice == null) ? null : choice.path("message");
            if (assistantMsg == null || assistantMsg.isMissingNode()) {
                // Si hubo error, devolver mensaje vacío al usuario
                if (first.has("error")) {
                    if (debug) System.out.println("[ChatService][DEBUG] choice.message missing and first has error; returning empty message.");
                    return applyFinalFallback(ResponseEnvelope.success("", DataSection.of("chat", List.of(), null), List.of(), List.of(MessageEntry.of("debug","openai_first_error_no_message"))));
                }
                String msg = first.has("text") ? first.path("text").asText("") : "";
                if (debug) System.out.println("[ChatService][DEBUG] choice.message missing, returning envelope.");
                return applyFinalFallback(ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of()));
            }

            if (!assistantMsg.has("tool_calls")) {
                String content = assistantMsg.path("content").asText("");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "No hay tool_calls, respuesta directa de OpenAI");
                if (debug) {
                    // Intento pretty-print si es JSON
                    try {
                        JsonNode tmp = om.readTree(content);
                        String pretty = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tmp);
                        System.out.println("[ChatService][DEBUG] No tool_calls, initial content (pretty):\n" + pretty);
                    } catch (Exception expp) {
                        System.out.println("[ChatService][DEBUG] No tool_calls, initial content: " + content);
                    }
                }
                // Si el contenido ya es JSON válido, procesarlo y convertirlo a envelope.
                try {
                    JsonNode contentNode = om.readTree(content);
                    ResponseEnvelope envDirect = buildEnvelopeFromContentNode(contentNode);
                    // Si el modelo devolvió JSON válido pero con 'text' vacío y sin items/paginación, reintentar con reformat + schema
                    // EXCEPCIÓN: Si el content original era texto plano inteligente, preservarlo
                    try {
                        boolean noItems = (envDirect.getData() == null) || (envDirect.getData().getItems() == null) || envDirect.getData().getItems().isEmpty();
                        boolean noPag = (envDirect.getData() == null) || (envDirect.getData().getPagination() == null);
                        String msg0 = envDirect.getMessage();
                        // Solo forza reformat si NO es un texto plano inteligente del primer turno
                        boolean isPlainTextIntelligent = !content.trim().startsWith("{") && content.length() > 10;
                        if ((msg0 == null || msg0.isBlank()) && noItems && noPag && !isPlainTextIntelligent) {
                            if (debug) System.out.println("[ChatService][DEBUG] Empty text with no tools/items; forcing reformat with schema");
                            List<Map<String, Object>> reSeed = new ArrayList<>();
                            reSeed.add(systemMsg);
                            if (profileJsonForPrompt != null && profileJsonForPrompt.trim().length() > 2 && !profileJsonForPrompt.trim().equals("{}")) {
                                reSeed.add(Map.of("role", "system", "content", "Perfil_usuario: " + profileJsonForPrompt));
                            }
                            // Reinyecta el contenido original del assistant como antecedente
                            reSeed.add(Map.of("role", "assistant", "content", content));
                            reSeed.add(Map.of("role", "user", "content", promptBuilder.buildReformatInstruction()));
                            String reformatted2 = openai.callChatNoToolsWithExtras(reSeed, openai.buildChatResponseSchemaExtras(), xmlLogger, authForToolCalls);
                            JsonNode rep2 = om.readTree(reformatted2);
                            if (rep2.has("choices")) {
                                String c2 = rep2.path("choices").get(0).path("message").path("content").asText("");
                                try { JsonNode n2 = om.readTree(c2); return applyFinalFallback(buildEnvelopeFromContentNode(n2)); } catch (Exception __p) { /* fallthrough */ }
                            }
                            try { JsonNode n2 = om.readTree(reformatted2); return applyFinalFallback(buildEnvelopeFromContentNode(n2)); } catch (Exception __p2) { /* fallthrough */ }
                        } else if (isPlainTextIntelligent) {
                            // Si era texto plano inteligente, crearlo como respuesta válida
                            if (debug) System.out.println("[ChatService][DEBUG] Preserving plain text intelligent response: " + content.substring(0, Math.min(100, content.length())));
                            return ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of());
                        }
                    } catch (Exception ignoreGuard) { }
                    return applyFinalFallback(envDirect);
                } catch (Exception e) {
                    // Intentamos pedir al modelo que convierta la respuesta anterior en JSON válido siguiendo el contrato
                    if (debug) System.out.println("[ChatService][DEBUG] Content not JSON, requesting reformat to JSON from OpenAI");
                    try {
                        boolean proceedWithToolCalls = false;
                        List<Map<String, Object>> reformatSeed = new ArrayList<>();
                        reformatSeed.add(systemMsg);
                        if (profileJsonForPrompt != null && profileJsonForPrompt.trim().length() > 2 && !profileJsonForPrompt.trim().equals("{}")) {
                            reformatSeed.add(Map.of("role", "system", "content", "Perfil_usuario: " + profileJsonForPrompt));
                        }
                        reformatSeed.add(Map.of("role", "assistant", "content", content));
                        // Instrucción clara y estricta para devolver JSON
                        String reformatInstruction = promptBuilder.buildReformatInstruction();
                        reformatSeed.add(Map.of("role", "user", "content", reformatInstruction));
                        // Enforce JSON schema so 'text' nunca venga vacío
                        String reformatted = openai.callChatNoToolsWithExtras(reformatSeed, openai.buildChatResponseSchemaExtras(), xmlLogger, authForToolCalls);
                        if (debug) {
                            try {
                                JsonNode tmpRef = om.readTree(reformatted == null ? "" : reformatted);
                                String prettyRef = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tmpRef);
                                System.out.println("[ChatService][DEBUG] Reformat response from OpenAI (pretty):\n" + prettyRef);
                            } catch (Exception ignore) {
                                System.out.println("[ChatService][DEBUG] Reformat response from OpenAI: " + (reformatted == null ? "<null>" : reformatted));
                            }
                        }
                        // El cliente puede devolver la respuesta completa; OpenAICallApiService devuelve un objeto completo 'choices' si no usamos callChatWithTools correctamente.
                        // Intentamos parsear la salida como JSON y devolverla si es válida.
                        try {
                            // Si la respuesta es un objeto completo (con choices), extraer contenido
                            JsonNode rep = om.readTree(reformatted);
                            if (rep.has("choices")) {
                                JsonNode msg2 = rep.path("choices").get(0).path("message");
                                // 1) Si trae content JSON directo, úsalo
                                JsonNode rc = msg2.path("content");
                                String rcStr = rc.isMissingNode() ? "" : rc.asText("");
                                try { JsonNode rcNode = om.readTree(rcStr); return buildEnvelopeFromContentNode(rcNode); } catch (Exception exx) { /* fall through */ }
                                // 2) Si trae tool_calls, sustituimos assistantMsg y continuamos el flujo normal (ejecutar tools)
                                if (msg2.has("tool_calls")) {
                                    assistantMsg = msg2; // reutilizar variable para seguir por el flujo de tool_calls
                                    proceedWithToolCalls = true;
                                }
                            }
                            if (proceedWithToolCalls) {
                                // Salimos del bloque 'no tool_calls' para continuar con la ejecución normal de tools
                            } else {
                                // Si la respuesta no contenía 'choices' o no devolvió JSON directo, intentamos parsearla como JSON directa
                                try {
                                    JsonNode reformNode = om.readTree(reformatted);
                                    return buildEnvelopeFromContentNode(reformNode);
                                } catch (Exception ex2) {
                                    // Fallback: devolver el texto original envuelto en text
                                    if (debug) System.out.println("[ChatService][DEBUG] Reformatting failed, returning fallback text JSON.");
                                    return applyFinalFallback(ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of()));
                                }
                            }
                        } catch (Exception ignore) {
                            // On parsing error, fallback
                            return applyFinalFallback(ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of()));
                        }
                    } catch (Exception ex) {
                        if (debug) System.out.println("[ChatService][DEBUG] Exception while reformatting content: " + ex.getMessage());
                        return applyFinalFallback(ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of()));
                    }
                }
                // Si llegamos aquí es porque reformat generó tool_calls y hemos reemplazado assistantMsg; continuamos flujo normal
            }

            // Delegated token: prefer the one created up-front (delegatedAuthUpfront) so we only create it once.
            String delegatedAuth = delegatedAuthUpfront;
            if (delegatedAuth == null) {
                try {
                    delegatedAuth = jwtDelegationService.createDelegatedAuthorizationHeader(claims);
                } catch (Exception dex) {
                    if (debug) System.out.println("[ChatService][DEBUG] Failed to create delegated token up-front: " + dex.getMessage());
                }
            }
            if (delegatedAuth == null) {
                // If we don't have a delegated token, abort the chat flow when tool_calls are requested.
                // We choose to return an explicit error JSON so clients/tests can detect the failure.
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Delegated token not available - aborting tool_calls");
                return ResponseEnvelope.error("Delegación deshabilitada","delegation_disabled","Delegation disabled or missing delegation secret - cannot proxy API calls");
            }

            // 3) Resolver tool_calls

            List<Map<String, Object>> toolOutputs = new ArrayList<>();
            // Keep metadata of executed tools to enable fast-path without a second OpenAI round-trip
            List<ExecMeta> executed = new ArrayList<>();
            // Métricas acumuladas de API para resumen de telemetría
            long totalApiMs = 0L;
            int totalCallsInBatch = 0;
            
            for (JsonNode tc : assistantMsg.path("tool_calls")) {
                String callId   = tc.path("id").asText();
                String funcName = tc.path("function").path("name").asText();
                String argsStr  = tc.path("function").path("arguments").asText("{}");
                JsonNode args;
                try {
                    args = om.readTree(argsStr);
                } catch (Exception eArgs) {
                    // If arguments are not valid JSON, wrap them into an object with raw string
                    if (debug) System.out.println("[ChatService][DEBUG] tool_call arguments not JSON, wrapping raw string");
                    args = om.createObjectNode();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) args).put("_raw_arguments", argsStr);
                }
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Procesando tool_call: " + funcName);
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Processing tool_call: callId=" + callId + ", funcName=" + funcName + ", args=" + args);
                }
                if (!"call_api".equals(funcName) && !"call_api_batch".equals(funcName)) {
                    toolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", "{\"error\":\"Tool no permitida: " + funcName + "\"}"
                    ));
                    if (xmlLogger != null) xmlLogger.addStep("ChatService", "Tool no permitida: " + funcName);
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] Tool not allowed: " + funcName);
                    }
                    continue;
                }
                if ("call_api_batch".equals(funcName)) {
                    // Ejecutar múltiples llamadas dentro de un único tool_call y devolver un resultado agregado
                    List<Map<String,Object>> batchResults = new ArrayList<>();
                    long batchStart = System.currentTimeMillis();
                    java.util.List<Long> perCallMs = new java.util.ArrayList<>();
                    try {
                        JsonNode calls = args.path("calls");
                        if (calls != null && calls.isArray()) {
                            for (JsonNode c : calls) {
                                String endpointNameB = c.path("name").asText();
                                String methodB = c.path("method").asText("GET");
                                JsonNode pathParamsB = c.path("pathParams");
                                JsonNode queryB = c.path("query");
                                JsonNode bodyB = c.path("body");
                                Map<String, Object> epB = openai.getEndpointByName(endpointNameB);
                                String apiResB;
                                long c0 = System.currentTimeMillis();
                                JsonNode qNormBForMeta = null;
                                if (epB == null) {
                                    apiResB = "{\"error\":\"Endpoint no permitido: " + endpointNameB + "\"}";
                                } else {
                                    try {
                                        String authToUseB = (delegatedAuth != null) ? delegatedAuth : authorization;
                                        // Aplicar token/heurística de navegación si falta 'page' en la query
                                        JsonNode qAppliedB = queryB;
                                        try {
                                            com.fasterxml.jackson.databind.node.ObjectNode qtemp = (qAppliedB != null && qAppliedB.isObject()) ? (com.fasterxml.jackson.databind.node.ObjectNode) qAppliedB.deepCopy() : om.createObjectNode();
                                            boolean hasPageB = qtemp.has("page") && qtemp.get("page").canConvertToInt();
                                            if (!hasPageB) {
                                                com.fasterxml.jackson.databind.node.ObjectNode tokB = extractPaginationToken(incoming);
                                                if (tokB != null) {
                                                    String tokenB = tokB.has("token") && tokB.get("token").isTextual() ? tokB.get("token").asText() : null;
                                                    Integer pageTokB = tokB.has("page") && tokB.get("page").canConvertToInt() ? tokB.get("page").asInt() : null;
                                                    Integer sizeTokB = tokB.has("size") && tokB.get("size").canConvertToInt() ? tokB.get("size").asInt() : null;
                                                    if (tokenB != null) {
                                                        java.util.Map<String,Object> payloadB = contextTokenService.verify(tokenB);
                                                        if (payloadB != null) {
                                                            Integer targetPageB = null;
                                                            try { Object tpB = payloadB.get("target_page"); if (tpB instanceof Number) targetPageB = ((Number) tpB).intValue(); } catch (Exception __i) {}
                                                            if (targetPageB != null) { qtemp.put("page", Math.max(1, targetPageB)); }
                                                            else if (pageTokB != null) { qtemp.put("page", Math.max(1, pageTokB)); }
                                                            else if (payloadB.get("page") instanceof Number) { qtemp.put("page", Math.max(1, ((Number) payloadB.get("page")).intValue())); }
                                                            if (sizeTokB != null) qtemp.put("size", Math.max(1, sizeTokB));
                                                            else if (payloadB.get("size") instanceof Number) qtemp.put("size", Math.max(1, ((Number) payloadB.get("size")).intValue()));
                                                            qAppliedB = qtemp;
                                                        }
                                                    }
                                                }
                                                // No aplicar heurística del historial cuando no hay token (orquestador puro)
                                            }
                                        } catch (Exception __bestEffort) {}
                                        // Enforce pagination defaults/caps for paginated GETs
                                        JsonNode qNormB = sanitizePagination(epB, methodB, qAppliedB);
                                        qNormBForMeta = qNormB;
                                        if (xmlLogger != null) {
                                            try {
                                                String qpB = (qNormB == null) ? "null" : qNormB.toString();
                                                String ppB = (pathParamsB == null) ? "null" : pathParamsB.toString();
                                                String bdB = (bodyB == null) ? "null" : bodyB.toString();
                                                xmlLogger.addStep("ApiProxyService", "[batch] Llamada a API: " + endpointNameB + " (" + methodB + ")" +
                                                        "<br>pathParams=" + ppB + "<br>query=" + qpB + "<br>body=" + bdB);
                                            } catch (Exception _ignore) {
                                                xmlLogger.addStep("ApiProxyService", "[batch] Llamada a API: " + endpointNameB + " (" + methodB + ")");
                                            }
                                        }
                                        if (xmlLogger != null) {
                                            apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, qNormB, bodyB, authToUseB, xmlLogger);
                                        } else {
                                            apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, qNormB, bodyB, authToUseB);
                                        }
                                    } catch (Exception exInnerB) {
                                        Map<String, Object> errMapB = Map.of("error", "authorization_failure", "message", exInnerB.getMessage() == null ? "" : exInnerB.getMessage());
                                        apiResB = om.writeValueAsString(errMapB);
                                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "Fallo autorización (batch): " + exInnerB.getMessage());
                                    }
                                }
                                long cMs = System.currentTimeMillis() - c0;
                                perCallMs.add(cMs);
                                // track executed meta por call individual
                                try {
                                    JsonNode parsedB = om.readTree(apiResB);
                                    ExecMeta metaB = new ExecMeta();
                                    metaB.endpointName = endpointNameB;
                                    metaB.method = methodB;
                                    metaB.apiResultNode = parsedB;
                                    metaB.raw = apiResB;
                                    // Enlazar metadatos básicos
                                    try {
                                        // Guardar la query efectiva ejecutada (post token/nav + saneamiento)
                                        metaB.querySnapshot = (qNormBForMeta == null) ? om.createObjectNode() : qNormBForMeta.deepCopy();
                                    } catch (Exception __ignore) { metaB.querySnapshot = om.createObjectNode(); }
                                    try { metaB.pathParamsSnapshot = (c.path("pathParams") == null) ? om.createObjectNode() : c.path("pathParams").deepCopy(); } catch (Exception __ignore) { metaB.pathParamsSnapshot = om.createObjectNode(); }
                                    try { if (epB != null) metaB.endpointPath = String.valueOf(epB.getOrDefault("path","")); } catch (Exception __ignore) { metaB.endpointPath = null; }
                                    executed.add(metaB);
                                    boolean okB = !(parsedB.has("error") || (parsedB.has("ok") && !parsedB.path("ok").asBoolean(true)));
                                    batchResults.add(Map.of("ok", okB, "result", parsedB));
                                } catch (Exception ignore) {
                                    batchResults.add(Map.of("ok", false, "result", apiResB));
                                }
                            }
                            totalCallsInBatch += calls.size();
                        } else {
                            batchResults.add(Map.of("ok", false, "result", Map.of("error","bad_arguments","message","'calls' debe ser un array")));
                        }
                    } catch (Exception exBatch) {
                        batchResults.add(Map.of("ok", false, "result", Map.of("error","exception","message", String.valueOf(exBatch.getMessage()))));
                    }
                    long batchTotalMs = System.currentTimeMillis() - batchStart;
                    totalApiMs += batchTotalMs;
                    String contentBatch = om.writeValueAsString(Map.of("ok", true, "results", batchResults));
                    toolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", contentBatch
                    ));
                    if (xmlLogger != null) {
                        try {
                            xmlLogger.addStep("Telemetry", "batch_metrics calls_in_batch=" + perCallMs.size() + ", batch_total_ms=" + batchTotalMs + ", per_call_ms=" + perCallMs);
                        } catch (Exception ignore) { }
                    }
                    continue;
                }
                String endpointName = args.path("name").asText();
                Map<String, Object> ep = openai.getEndpointByName(endpointName);
                if (ep == null) {
                    toolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", "{\"error\":\"Endpoint no permitido: " + endpointName + "\"}"
                    ));
                    if (xmlLogger != null) xmlLogger.addStep("ChatService", "Endpoint no permitido: " + endpointName);
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] Endpoint not allowed: " + endpointName);
                    }
                    continue;
                }
                String methodFromModel = args.path("method").asText("GET");
                JsonNode pathParams    = args.path("pathParams");
                JsonNode query         = args.path("query");
                JsonNode body          = args.path("body");
                // Navegación con token: si el cliente aceptó una ui_suggestion de paginación y envió Paginacion_token, aplicar page/size
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode q = (query != null && query.isObject()) ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode();
                    boolean hasPage = q.has("page") && q.get("page").canConvertToInt();
                    if (!hasPage) {
                        com.fasterxml.jackson.databind.node.ObjectNode tok = extractPaginationToken(incoming);
                        if (tok != null) {
                            String token = tok.has("token") && tok.get("token").isTextual() ? tok.get("token").asText() : null;
                            Integer pageTok = tok.has("page") && tok.get("page").canConvertToInt() ? tok.get("page").asInt() : null;
                            Integer sizeTok = tok.has("size") && tok.get("size").canConvertToInt() ? tok.get("size").asInt() : null;
                            if (token != null) {
                                java.util.Map<String,Object> payload = contextTokenService.verify(token);
                                if (payload != null) {
                                    // Preferir target_page del token si está presente; si no, usar pageTok o payload.page
                                    Integer targetPage = null;
                                    try {
                                        Object tp = payload.get("target_page");
                                        if (tp instanceof Number) targetPage = ((Number) tp).intValue();
                                    } catch (Exception ignore2) { }
                                    if (targetPage != null) {
                                        q.put("page", Math.max(1, targetPage));
                                    } else if (pageTok != null) {
                                        q.put("page", Math.max(1, pageTok));
                                    } else if (payload.get("page") instanceof Number) {
                                        q.put("page", Math.max(1, ((Number) payload.get("page")).intValue()));
                                    }
                                    if (sizeTok != null) q.put("size", Math.max(1, sizeTok));
                                    else if (payload.get("size") instanceof Number) q.put("size", Math.max(1, ((Number) payload.get("size")).intValue()));
                                    query = q;
                                }
                            }
                        }
                        // No aplicar heurísticas de navegación basadas en historial si no hay token (orquestador puro)
                    }
                } catch (Exception ignore) { /* best-effort token nav */ }
                if (xmlLogger != null) {
                    try {
                        String qp = (query == null) ? "null" : query.toString();
                        String pp = (pathParams == null) ? "null" : pathParams.toString();
                        String bd = (body == null) ? "null" : body.toString();
                        xmlLogger.addStep("ApiProxyService", "Llamada a API: " + endpointName + " (" + methodFromModel + ")" +
                                "<br>pathParams=" + pp + "<br>query=" + qp + "<br>body=" + bd);
                    } catch (Exception _ignore) {
                        xmlLogger.addStep("ApiProxyService", "Llamada a API: " + endpointName + " (" + methodFromModel + ")");
                    }
                }
                // Log masked Authorization preview + short SHA so the generated HTML trace includes
                // the exact token fingerprint used for this proxied request. This helps detect
                // whether the token changes between entry and the actual API call.
                // Create delegated token once on demand (if not already created)
                if (delegatedAuth == null) {
                    try {
                        delegatedAuth = jwtDelegationService.createDelegatedAuthorizationHeader(claims);
                        if (debug) {
                            try {
                                String usedPreview = delegatedAuth == null ? "<none>" : (delegatedAuth.length() > 12 ? delegatedAuth.substring(0,8) + "..." : delegatedAuth);
                                java.security.MessageDigest md2 = java.security.MessageDigest.getInstance("SHA-256");
                                byte[] digest2 = delegatedAuth == null ? new byte[0] : md2.digest(delegatedAuth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                StringBuilder sb2 = new StringBuilder();
                                for (int i = 0; i < 4 && i < digest2.length; i++) sb2.append(String.format("%02x", digest2[i]));
                                System.out.println("[ChatService][DEBUG] Delegated token produced (masked)=" + usedPreview + ", token_sha4=" + sb2.toString());
                            } catch (Exception eLog) { System.out.println("[ChatService][DEBUG] Could not compute delegated token fingerprint: " + eLog.getMessage()); }
                        }
                    } catch (Exception dex) {
                        if (debug) System.out.println("[ChatService][DEBUG] Failed to create delegated token: " + dex.getMessage());
                    }
                }
                if (xmlLogger != null && authorization != null) {
                    try {
                        String a = (delegatedAuth != null) ? delegatedAuth : authorization;
                        String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                        xmlLogger.addStep("ApiProxyService", "Authorization preview=" + preview + ", token_sha4=" + sb.toString());
                    } catch (Exception ignore) { }
                }
                if (debug) {
                    // Make it explicit in logs whether we're using a delegated token or forwarding the original
                    try {
                        String used = (delegatedAuth != null) ? "delegated" : "original";
                        String a = (delegatedAuth != null) ? delegatedAuth : authorization;
                        String preview = a == null ? "<none>" : (a.length() > 12 ? a.substring(0, 8) + "..." : a);
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                        byte[] digest = a == null ? new byte[0] : md.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                        System.out.println("[ChatService][DEBUG] About to call ApiProxyService (using " + used + " token). Authorization preview=" + preview + ", token_sha4=" + sb.toString());
                    } catch (Exception exx) { System.out.println("[ChatService][DEBUG] About to call ApiProxyService (used token), but failed computing fingerprint: " + exx.getMessage()); }
                    logger.debug("[ChatService] Calling ApiProxyService: endpoint={}, method={}, pathParams={}, query={}, body={}", endpointName, methodFromModel, pathParams, query, body);
                }
                    String apiResult;
                try {
                    long apiStartMs = System.currentTimeMillis();
                    // Antes de ejecutar, validar permisos y sanitizar parámetros
                    try {
                        // Prefer delegated token if available, otherwise forward original authorization
                        String authToUse = (delegatedAuth != null) ? delegatedAuth : authorization;
                        // Enforce pagination defaults/caps for paginated GET endpoints
                        JsonNode qNorm = sanitizePagination(ep, methodFromModel, query);
                        if (xmlLogger != null) {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, qNorm, body, authToUse, xmlLogger);
                        } else {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, qNorm, body, authToUse);
                        }
                    } catch (Exception exInner) {
                        // Ensure apiResult is always a JSON string describing the error
                        Map<String, Object> errMap = Map.of("error", "authorization_failure", "message", exInner.getMessage() == null ? "" : exInner.getMessage());
                        apiResult = om.writeValueAsString(errMap);
                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "Fallo autorización: " + exInner.getMessage());
                    }
                    long apiElapsedMs = System.currentTimeMillis() - apiStartMs;
                    totalApiMs += apiElapsedMs;
                    if (xmlLogger != null) xmlLogger.addStep("Telemetry", "api_call_ms=" + apiElapsedMs + " (single)");
                    if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Respuesta recibida de API: " + endpointName + " (result_snippet=" + (apiResult == null ? "" : (apiResult.length() > 200 ? apiResult.substring(0, 200) + "..." : apiResult)) + ")");
                    if (debug) logger.debug("[ChatService] ApiProxyService result snippet: {}", (apiResult == null ? "" : (apiResult.length() > 200 ? apiResult.substring(0,200) + "..." : apiResult)));
                } catch (Exception ex) {
                    apiResult = "{\"error\":\"Fallo al llamar API: " + ex.getMessage() + "\"}";
                    if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Excepción al llamar API: " + ex.getMessage());
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] ApiProxyService exception: " + ex.getMessage());
                    }
                }
                // Normalize apiResult: if the ApiProxyService returned plain text (errors, stack traces, etc.)
                // ensure we always work with a valid JSON string. This prevents Jackson JsonParseException
                // when downstream logic tries to parse the API result.
                // Ensure apiResult is valid JSON; ApiProxyService should already return JSON on errors,
                // but be defensive and normalize anything unexpected.
                JsonNode parsedApiNode;
                try {
                    parsedApiNode = om.readTree(apiResult);
                } catch (Exception parseEx) {
                    try {
                        Map<String, String> err = Map.of("error", apiResult == null ? "" : apiResult);
                        apiResult = om.writeValueAsString(err);
                        if (debug) System.out.println("[ChatService][DEBUG] Normalized non-JSON API result into JSON error object.");
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Normalized non-JSON API result into JSON error object");
                        parsedApiNode = om.readTree(apiResult);
                    } catch (Exception wrapEx) {
                        String safe = apiResult == null ? "" : apiResult.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                        apiResult = "{\"error\":\"" + safe + "\"}";
                        if (debug) System.out.println("[ChatService][DEBUG] Fallback normalization applied to API result.");
                        parsedApiNode = om.readTree(apiResult);
                    }
                }

                // Notas: ya no se hace "early return" para objetos únicos. Siempre reinyectamos en el segundo turno
                // para que sea la IA quien genere el 'text' y 'suggestions'.
                toolOutputs.add(Map.of(
                    "role", "tool",
                    "tool_call_id", callId,
                    "content", apiResult
                ));

                // Save execution metadata for optional fast-path
                ExecMeta meta = new ExecMeta();
                meta.endpointName = endpointName;
                meta.method = methodFromModel;
                meta.apiResultNode = parsedApiNode;
                meta.raw = apiResult;
                try {
                    // Guardar la query efectiva usada (tras saneamiento de paginación)
                    JsonNode qEff = sanitizePagination(ep, methodFromModel, (query == null) ? om.createObjectNode() : query);
                    meta.querySnapshot = (qEff == null) ? om.createObjectNode() : qEff.deepCopy();
                } catch (Exception __ignore) { meta.querySnapshot = om.createObjectNode(); }
                try { meta.endpointPath = String.valueOf(ep.getOrDefault("path","")); } catch (Exception __ignore) { meta.endpointPath = null; }
                executed.add(meta);
            }


            // 4) Segundo turno LITE desactivado: siempre delegamos el texto al modelo (no polish/backend)

            // 5) Fast-path pre-2º turno desactivado: no generamos texto/sugerencias desde backend

            // 6) Segundo turno normal: si excede presupuesto, lo marcamos en traza, pero seguimos y dejamos que el modelo redacte
            try {
                long elapsedBeforeSecond = System.currentTimeMillis() - t0;
                long budget = parametros.getSecondTurnBudgetMs();
                if (elapsedBeforeSecond > budget && xmlLogger != null) {
                    xmlLogger.addStep("Telemetry", "decision_budget_exceeded=true budget_ms=" + budget + " elapsed_ms=" + elapsedBeforeSecond);
                }
            } catch (Exception ignore) { /* best-effort budget check */ }

            // 6) Segundo turno normal: reinyectamos el assistant con sus tool_calls + los outputs
            // Construimos un seed compacto para el segundo turno para reducir tokens
            List<Map<String, Object>> followup = new ArrayList<>();
            followup.add(Map.of("role","system","content", promptBuilder.buildSecondTurnSystemPrompt()));
            if (profileJsonForPrompt != null && profileJsonForPrompt.trim().length() > 2 && !profileJsonForPrompt.trim().equals("{}")) {
                followup.add(Map.of("role", "system", "content", "Perfil_usuario: " + profileJsonForPrompt));
            }
            // Sin inserción/almacenado de Contexto_paginacion: navegación será por ui_suggestions + contextToken

            Map<String, Object> assistantEcho = new HashMap<>();
            assistantEcho.put("role", "assistant");
            assistantEcho.put("content",
                assistantMsg.path("content").isMissingNode() ? "" : assistantMsg.path("content").asText("")
            );
            assistantEcho.put("tool_calls", om.convertValue(assistantMsg.path("tool_calls"), List.class));
            followup.add(assistantEcho);
            // Eco recortado: limitar arrays en toolOutputs a una muestra pequeña + metadatos
            // Dinámico: si detectamos listados grandes en la ejecución (size>=30 o returned>=30), reducir aún más la muestra
            int sampleN = Math.max(0, parametros.getModelEchoSampleSize());
            int sampleNUsed = sampleN;
            boolean largeListContext = false;
            try {
                if (executed != null && !executed.isEmpty()) {
                    for (ExecMeta em : executed) {
                        if (em == null || em.apiResultNode == null) continue;
                        ItemsAndPagination f = findItemsArray(em.apiResultNode, extractTargetFromEndpointName(em.endpointName));
                        int ret = (f == null || f.items == null) ? 0 : f.items.size();
                        int sz = 0;
                        try { if (em.querySnapshot != null && em.querySnapshot.has("size") && em.querySnapshot.get("size").canConvertToInt()) sz = em.querySnapshot.get("size").asInt(); } catch (Exception __i) {}
                        if (sz >= 30 || ret >= 30) { largeListContext = true; break; }
                    }
                }
                if (largeListContext) {
                    // Evitar ambigüedad de "1" ítem en eco: usar al menos 2 y como máximo 3
                    int candidate = Math.min(sampleN, 3);
                    sampleNUsed = Math.max(2, candidate);
                }
            } catch (Exception __dynEcho) { /* best-effort */ }
            long reinjectPayloadBytes = 0L;
            for (Map<String,Object> to : toolOutputs) {
                try {
                    Object contentObj = to.get("content");
                    String contentStr = contentObj == null ? null : String.valueOf(contentObj);
                    JsonNode cnode = contentStr == null ? null : om.readTree(contentStr);
                    if (sampleNUsed > 0 && cnode != null && cnode.isObject()) {
                        // Caso A: wrapper batch => results[].result.{array}
                        if (cnode.has("results") && cnode.get("results").isArray()) {
                            com.fasterxml.jackson.databind.node.ObjectNode trimmedBatch = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                            com.fasterxml.jackson.databind.node.ArrayNode results = (com.fasterxml.jackson.databind.node.ArrayNode) trimmedBatch.get("results");
                            for (int ri = 0; ri < results.size(); ri++) {
                                JsonNode riNode = results.get(ri);
                                if (riNode != null && riNode.isObject()) {
                                    JsonNode resultNode = riNode.get("result");
                                    if (resultNode != null && resultNode.isObject()) {
                                        com.fasterxml.jackson.databind.node.ObjectNode rObj = (com.fasterxml.jackson.databind.node.ObjectNode) resultNode.deepCopy();
                                        JsonNode items = null;
                                        for (String k : parametros.getAllowedTargetsPlural()) {
                                            if (rObj.has(k) && rObj.get(k).isArray()) { items = rObj.get(k); break; }
                                        }
                                        if (items == null && rObj.has("items") && rObj.get("items").isArray()) items = rObj.get("items");
                                        if (items != null && items.isArray() && items.size() > sampleNUsed) {
                                            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                                            for (int i=0;i<sampleNUsed;i++) arr.add(projectItem(items.get(i)));
                                            boolean replaced = false;
                                            for (String k : parametros.getAllowedTargetsPlural()) {
                                                if (rObj.has(k) && rObj.get(k).isArray()) { rObj.set(k, arr); replaced = true; break; }
                                            }
                                            if (!replaced && rObj.has("items") && rObj.get("items").isArray()) rObj.set("items", arr);
                                            rObj.put("returned", items.size());
                                            rObj.put("sample_of", sampleNUsed);
                                            ((com.fasterxml.jackson.databind.node.ObjectNode) riNode).set("result", rObj);
                                        }
                                    }
                                }
                            }
                            to = new HashMap<>(to);
                            to.put("content", om.writeValueAsString(trimmedBatch));
                        } else {
                            // Caso B: tool output plano
                            JsonNode items = null;
                            for (String k : parametros.getAllowedTargetsPlural()) {
                                if (cnode.has(k) && cnode.get(k).isArray()) { items = cnode.get(k); break; }
                            }
                            if (items == null && cnode.has("items") && cnode.get("items").isArray()) items = cnode.get("items");
                            if (items != null && items.size() > sampleNUsed) {
                                com.fasterxml.jackson.databind.node.ObjectNode trimmed = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                                com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                                for (int i=0;i<sampleNUsed;i++) arr.add(projectItem(items.get(i)));
                                boolean replaced = false;
                                for (String k : parametros.getAllowedTargetsPlural()) {
                                    if (trimmed.has(k) && trimmed.get(k).isArray()) { trimmed.set(k, arr); replaced = true; break; }
                                }
                                if (!replaced && trimmed.has("items") && trimmed.get("items").isArray()) trimmed.set("items", arr);
                                trimmed.put("returned", items.size());
                                trimmed.put("sample_of", sampleNUsed);
                                to = new HashMap<>(to);
                                to.put("content", om.writeValueAsString(trimmed));
                            }
                        }
                    }
                } catch (Exception ignore) {}
                try {
                    Object contentObj2 = to.get("content");
                    String contentStr2 = contentObj2 == null ? null : String.valueOf(contentObj2);
                    if (contentStr2 != null) reinjectPayloadBytes += contentStr2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                } catch (Exception _ignore) {}
                followup.add(to);
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda llamada a OpenAI (reinyectando resultados de tools)");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Calling OpenAI with followup messages: " + followup);
                }
            if (xmlLogger != null) {
                try {
                    long followupBytes = 0L;
                    for (Map<String,Object> m : followup) {
                        Object cObj = m.get("content");
                        if (cObj != null) followupBytes += String.valueOf(cObj).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    }
                    xmlLogger.addStep("Telemetry", "api_ms_total=" + totalApiMs + ", calls_in_batch=" + totalCallsInBatch + ", reinject_payload_bytes=" + reinjectPayloadBytes + ", followup_messages=" + followup.size() + ", followup_bytes=" + followupBytes);
                    xmlLogger.addStep("Telemetry", "echo_sample_used=" + sampleNUsed + ", large_list_context=" + largeListContext);
                } catch (Exception ignore) { }
            }
            // Inyección de instrucción de segundo turno con resumen compacto
            try {
                String miniResumen = buildMiniResumen(executed, sampleNUsed);
                followup.add(java.util.Map.of("role","user","content", promptBuilder.buildSecondTurnInstruction(miniResumen)));
            } catch (Exception __ignoreInstr2) { /* best-effort */ }
            long secondStartMs = System.currentTimeMillis();
            String secondRawSafe = null;
            JsonNode second = null;
            try {
                // FIX: Usar callChatWithToolsWithExtras para permitir flujo multi-paso (ej: GET /roles → POST /usuarios)
                // La instrucción del segundo turno ahora permite herramientas si son necesarias para completar operaciones
                secondRawSafe = openai.callChatWithToolsWithExtras(followup, openai.buildChatResponseSchemaExtras(), xmlLogger, authorization);
                if (secondRawSafe == null || secondRawSafe.isBlank()) {
                    if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda llamada devolvió cuerpo vacío/null; usando fallback seguro");
                } else {
                    second = om.readTree(secondRawSafe);
                }
            } catch (Exception exSecond) {
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Excepción en segunda llamada/parsing: " + exSecond.getMessage());
            }
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "second_ms=" + (System.currentTimeMillis() - secondStartMs));
            // Telemetría: fin de la primera llamada del segundo turno
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "decision_ms_first_second_turn=" + (System.currentTimeMillis() - t0));
            if (debug) logger.debug("[Telemetry] decision_ms_first_second_turn={} (since start)", (System.currentTimeMillis() - t0));
            if (debug) {
                try {
                    String prettySecond = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(second);
                    System.out.println("[ChatService][DEBUG] OpenAI second response (pretty):\n" + prettySecond);
                } catch (Exception ignore) {
                    System.out.println("[ChatService][DEBUG] OpenAI second response: " + (secondRawSafe == null ? "<null>" : secondRawSafe));
                }
            }
            // Defensive: ensure we have choices; if not, return a safe error envelope
            JsonNode finalMsg;
            try {
                if (second == null) {
                    if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda respuesta nula; devolviendo envelope de fallback seguro");
                    return applyFinalFallback(ResponseEnvelope.error("No se pudo completar la respuesta (segunda fase)", "openai_empty_response", "Segunda respuesta vacía"));
                }
                JsonNode choices2 = second.path("choices");
                if (choices2 == null || !choices2.isArray() || choices2.size() == 0) {
                    if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda respuesta sin choices: devolviendo envelope de error controlado");
                    return applyFinalFallback(ResponseEnvelope.error("No se pudo completar la respuesta (segunda fase)", "openai_empty_response", "Segunda respuesta sin choices"));
                }
                finalMsg = choices2.get(0).path("message");
            } catch (Exception ex) {
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Excepción accediendo a choices: " + ex.getMessage());
                return applyFinalFallback(ResponseEnvelope.error("Error al procesar respuesta", "openai_parse_error", ex.getMessage()));
            }
            // Bucle limitado: si el 2º turno devuelve tool_calls, ejecútalos y vuelve a llamar (máx 2 iteraciones o 8s presupuesto)
            int extraIters = 0;
            final long iterationBudgetMs = parametros.getSecondTurnBudgetMs();
            final int maxIters = Math.max(0, parametros.getSecondTurnMaxExtraIterations());
            while (finalMsg != null && finalMsg.has("tool_calls") && extraIters < maxIters && (System.currentTimeMillis() - t0) < iterationBudgetMs) {
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Segundo turno - iteración extra " + (extraIters+1) + ": procesando tool_calls");
                // Ejecutar tool_calls devueltos por el 2º turno
                List<Map<String, Object>> iterToolOutputs = new ArrayList<>();
                long iterApiMs = 0L;
                for (JsonNode tc : finalMsg.path("tool_calls")) {
                    String callId   = tc.path("id").asText();
                    String funcName = tc.path("function").path("name").asText();
                    String argsStr  = tc.path("function").path("arguments").asText("{}");
                    JsonNode args;
                    try { args = om.readTree(argsStr); } catch (Exception eArgs) {
                        args = om.createObjectNode();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) args).put("_raw_arguments", argsStr);
                    }
                    if (!"call_api".equals(funcName) && !"call_api_batch".equals(funcName)) {
                        iterToolOutputs.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", "{\"error\":\"Tool no permitida: " + funcName + "\"}"
                        ));
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Tool no permitida (iter): " + funcName);
                        continue;
                    }
                    if ("call_api_batch".equals(funcName)) {
                        List<Map<String,Object>> batchResults = new ArrayList<>();
                        long batchStartIter = System.currentTimeMillis();
                        java.util.List<Long> perCallMsIter = new java.util.ArrayList<>();
                        try {
                            JsonNode calls = args.path("calls");
                            if (calls != null && calls.isArray()) {
                                for (JsonNode c : calls) {
                                    String endpointNameB = c.path("name").asText();
                                    String methodB = c.path("method").asText("GET");
                                    JsonNode pathParamsB = c.path("pathParams");
                                    JsonNode queryB = c.path("query");
                                    JsonNode bodyB = c.path("body");
                                    Map<String, Object> epB = openai.getEndpointByName(endpointNameB);
                                    String apiResB;
                                    long c0 = System.currentTimeMillis();
                                    if (epB == null) {
                                        apiResB = "{\"error\":\"Endpoint no permitido: " + endpointNameB + "\"}";
                                    } else {
                                        try {
                                            String authToUseB = (delegatedAuth != null) ? delegatedAuth : authorization;
                                            // Aplicar token/heurística de navegación si falta 'page' en la query
                                            JsonNode qAppliedB = queryB;
                                            try {
                                                com.fasterxml.jackson.databind.node.ObjectNode qtemp = (qAppliedB != null && qAppliedB.isObject()) ? (com.fasterxml.jackson.databind.node.ObjectNode) qAppliedB.deepCopy() : om.createObjectNode();
                                                boolean hasPageB = qtemp.has("page") && qtemp.get("page").canConvertToInt();
                                                if (!hasPageB) {
                                                    com.fasterxml.jackson.databind.node.ObjectNode tokB = extractPaginationToken(incoming);
                                                    if (tokB != null) {
                                                        String tokenB = tokB.has("token") && tokB.get("token").isTextual() ? tokB.get("token").asText() : null;
                                                        Integer pageTokB = tokB.has("page") && tokB.get("page").canConvertToInt() ? tokB.get("page").asInt() : null;
                                                        Integer sizeTokB = tokB.has("size") && tokB.get("size").canConvertToInt() ? tokB.get("size").asInt() : null;
                                                        if (tokenB != null) {
                                                            java.util.Map<String,Object> payloadB = contextTokenService.verify(tokenB);
                                                            if (payloadB != null) {
                                                                Integer targetPageB = null;
                                                                try { Object tpB = payloadB.get("target_page"); if (tpB instanceof Number) targetPageB = ((Number) tpB).intValue(); } catch (Exception __i) {}
                                                                if (targetPageB != null) { qtemp.put("page", Math.max(1, targetPageB)); }
                                                                else if (pageTokB != null) { qtemp.put("page", Math.max(1, pageTokB)); }
                                                                else if (payloadB.get("page") instanceof Number) { qtemp.put("page", Math.max(1, ((Number) payloadB.get("page")).intValue())); }
                                                                if (sizeTokB != null) qtemp.put("size", Math.max(1, sizeTokB));
                                                                else if (payloadB.get("size") instanceof Number) qtemp.put("size", Math.max(1, ((Number) payloadB.get("size")).intValue()));
                                                                qAppliedB = qtemp;
                                                            }
                                                        }
                                                    }
                                                    // No aplicar heurística del historial cuando no hay token (orquestador puro)
                                                }
                                            } catch (Exception __bestEffort) {}
                                            // Enforce pagination defaults/caps for paginated GETs
                                            JsonNode qNormB = sanitizePagination(epB, methodB, qAppliedB);
                                            if (xmlLogger != null) {
                                                apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, qNormB, bodyB, authToUseB, xmlLogger);
                                            } else {
                                                apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, qNormB, bodyB, authToUseB);
                                            }
                                        } catch (Exception exInnerB) {
                                            Map<String, Object> errMapB = Map.of("error", "authorization_failure", "message", exInnerB.getMessage() == null ? "" : exInnerB.getMessage());
                                            apiResB = om.writeValueAsString(errMapB);
                                            if (xmlLogger != null) xmlLogger.addStep("Authorization", "[iter] Fallo autorización (batch): " + exInnerB.getMessage());
                                        }
                                    }
                                    long cMs = System.currentTimeMillis() - c0;
                                    perCallMsIter.add(cMs);
                                    try {
                                        JsonNode parsedB = om.readTree(apiResB);
                                        ExecMeta metaB = new ExecMeta();
                                        metaB.endpointName = endpointNameB;
                                        metaB.method = methodB;
                                        metaB.apiResultNode = parsedB;
                                        metaB.raw = apiResB;
                                        try {
                                            JsonNode qEff = sanitizePagination(epB, methodB, (c.path("query") == null) ? om.createObjectNode() : c.path("query"));
                                            metaB.querySnapshot = (qEff == null) ? om.createObjectNode() : qEff.deepCopy();
                                        } catch (Exception __ignore) { metaB.querySnapshot = om.createObjectNode(); }
                                        executed.add(metaB);
                                        boolean okB = !(parsedB.has("error") || (parsedB.has("ok") && !parsedB.path("ok").asBoolean(true)));
                                        batchResults.add(Map.of("ok", okB, "result", parsedB));
                                    } catch (Exception ignore) {
                                        batchResults.add(Map.of("ok", false, "result", apiResB));
                                    }
                                }
                            } else {
                                batchResults.add(Map.of("ok", false, "result", Map.of("error","bad_arguments","message","'calls' debe ser un array")));
                            }
                        } catch (Exception exBatch) {
                            batchResults.add(Map.of("ok", false, "result", Map.of("error","exception","message", String.valueOf(exBatch.getMessage()))));
                        }
                        long batchTotalIter = System.currentTimeMillis() - batchStartIter;
                        iterApiMs += batchTotalIter;
                        String contentBatch = om.writeValueAsString(Map.of("ok", true, "results", batchResults));
                        iterToolOutputs.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", contentBatch
                        ));
                        if (xmlLogger != null) {
                            try { xmlLogger.addStep("Telemetry", "[iter] batch_metrics calls_in_batch=" + perCallMsIter.size() + ", batch_total_ms=" + batchTotalIter + ", per_call_ms=" + perCallMsIter); } catch (Exception ignore) {}
                        }
                        continue;
                    }
                    String endpointName = args.path("name").asText();
                    Map<String, Object> ep = openai.getEndpointByName(endpointName);
                    if (ep == null) {
                        iterToolOutputs.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", "{\"error\":\"Endpoint no permitido: " + endpointName + "\"}"
                        ));
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Endpoint no permitido (iter): " + endpointName);
                        continue;
                    }
                    String methodFromModel = args.path("method").asText("GET");
                    JsonNode pathParams    = args.path("pathParams");
                    JsonNode query         = args.path("query");
                    JsonNode body          = args.path("body");
                    if (xmlLogger != null) {
                        try {
                            String qp = (query == null) ? "null" : query.toString();
                            String pp = (pathParams == null) ? "null" : pathParams.toString();
                            String bd = (body == null) ? "null" : body.toString();
                            xmlLogger.addStep("ApiProxyService", "[iter] Llamada a API: " + endpointName + " (" + methodFromModel + ")" +
                                    "<br>pathParams=" + pp + "<br>query=" + qp + "<br>body=" + bd);
                        } catch (Exception _ignore) {
                            xmlLogger.addStep("ApiProxyService", "[iter] Llamada a API: " + endpointName + " (" + methodFromModel + ")");
                        }
                    }
                    if (delegatedAuth == null) {
                        try { delegatedAuth = jwtDelegationService.createDelegatedAuthorizationHeader(claims); } catch (Exception ignore) {}
                    }
                    String apiResult;
                    try {
                        long apiStartMs = System.currentTimeMillis();
                        String authToUse = (delegatedAuth != null) ? delegatedAuth : authorization;
                        if (xmlLogger != null) {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse, xmlLogger);
                        } else {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse);
                        }
                        long apiElapsedMs = System.currentTimeMillis() - apiStartMs;
                        iterApiMs += apiElapsedMs;
                        if (xmlLogger != null) xmlLogger.addStep("Telemetry", "[iter] api_call_ms=" + apiElapsedMs + " (single)");
                    } catch (Exception exInner) {
                        Map<String, Object> errMap = Map.of("error", "authorization_failure", "message", exInner.getMessage() == null ? "" : exInner.getMessage());
                        apiResult = om.writeValueAsString(errMap);
                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "[iter] Fallo autorización: " + exInner.getMessage());
                    }
                    // Parse/normalize apiResult a JSON
                    JsonNode parsedApiNode;
                    try { parsedApiNode = om.readTree(apiResult); } catch (Exception parseEx) {
                        try {
                            Map<String, String> err = Map.of("error", apiResult == null ? "" : apiResult);
                            apiResult = om.writeValueAsString(err);
                            parsedApiNode = om.readTree(apiResult);
                        } catch (Exception wrapEx) {
                            String safe = apiResult == null ? "" : apiResult.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                            apiResult = "{\"error\":\"" + safe + "\"}";
                            parsedApiNode = om.readTree(apiResult);
                        }
                    }
                    iterToolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", apiResult
                    ));
                    // Añadir a ejecutados para enriquecer al final si procede
                    ExecMeta meta = new ExecMeta();
                    meta.endpointName = endpointName;
                    meta.method = methodFromModel;
                    meta.apiResultNode = parsedApiNode;
                    meta.raw = apiResult;
                    try { meta.querySnapshot = (query == null) ? om.createObjectNode() : query.deepCopy(); } catch (Exception __ignore) { meta.querySnapshot = om.createObjectNode(); }
                    try { meta.endpointPath = String.valueOf(ep.getOrDefault("path","")); } catch (Exception __ignore) { meta.endpointPath = null; }
                    try { meta.pathParamsSnapshot = (pathParams == null) ? om.createObjectNode() : pathParams.deepCopy(); } catch (Exception __ignore) { meta.pathParamsSnapshot = om.createObjectNode(); }
                    executed.add(meta);
                }
                // Construir nuevo followup para la siguiente iteración (aplicando también eco recortado)
                Map<String, Object> iterAssistantEcho = new HashMap<>();
                iterAssistantEcho.put("role", "assistant");
                iterAssistantEcho.put("content", finalMsg.path("content").isMissingNode() ? "" : finalMsg.path("content").asText(""));
                iterAssistantEcho.put("tool_calls", om.convertValue(finalMsg.path("tool_calls"), List.class));
                followup.add(iterAssistantEcho);
                int sampleN2 = Math.max(0, parametros.getModelEchoSampleSize());
                int sampleN2Used = sampleN2;
                boolean largeListContext2 = false;
                try {
                    if (executed != null && !executed.isEmpty()) {
                        for (ExecMeta em : executed) {
                            if (em == null || em.apiResultNode == null) continue;
                            ItemsAndPagination f = findItemsArray(em.apiResultNode, extractTargetFromEndpointName(em.endpointName));
                            int ret = (f == null || f.items == null) ? 0 : f.items.size();
                            int sz = 0;
                            try { if (em.querySnapshot != null && em.querySnapshot.has("size") && em.querySnapshot.get("size").canConvertToInt()) sz = em.querySnapshot.get("size").asInt(); } catch (Exception __i) {}
                            if (sz >= 30 || ret >= 30) { largeListContext2 = true; break; }
                        }
                    }
                    if (largeListContext2) {
                        // Evitar ambigüedad: mínimo 2 ítems en eco para listas grandes
                        int candidate2 = Math.min(sampleN2, 3);
                        sampleN2Used = Math.max(2, candidate2);
                    }
                } catch (Exception __dynEcho2) { /* best-effort */ }
                long iterReinjectBytes = 0L;
                for (Map<String,Object> to2 : iterToolOutputs) {
                    try {
                        Object contentObj = to2.get("content");
                        String contentStr = contentObj == null ? null : String.valueOf(contentObj);
                        JsonNode cnode = contentStr == null ? null : om.readTree(contentStr);
                        if (sampleN2Used > 0 && cnode != null && cnode.isObject()) {
                            if (cnode.has("results") && cnode.get("results").isArray()) {
                                com.fasterxml.jackson.databind.node.ObjectNode trimmedBatch = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                                com.fasterxml.jackson.databind.node.ArrayNode results = (com.fasterxml.jackson.databind.node.ArrayNode) trimmedBatch.get("results");
                                for (int ri = 0; ri < results.size(); ri++) {
                                    JsonNode riNode = results.get(ri);
                                    if (riNode != null && riNode.isObject()) {
                                        JsonNode resultNode = riNode.get("result");
                                        if (resultNode != null && resultNode.isObject()) {
                                            com.fasterxml.jackson.databind.node.ObjectNode rObj = (com.fasterxml.jackson.databind.node.ObjectNode) resultNode.deepCopy();
                                            JsonNode items = null;
                                            for (String k : parametros.getAllowedTargetsPlural()) {
                                                if (rObj.has(k) && rObj.get(k).isArray()) { items = rObj.get(k); break; }
                                            }
                                            if (items == null && rObj.has("items") && rObj.get("items").isArray()) items = rObj.get("items");
                                            if (items != null && items.isArray() && items.size() > sampleN2Used) {
                                                com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                                                for (int i=0;i<sampleN2Used;i++) arr.add(projectItem(items.get(i)));
                                                boolean replaced = false;
                                                for (String k : parametros.getAllowedTargetsPlural()) {
                                                    if (rObj.has(k) && rObj.get(k).isArray()) { rObj.set(k, arr); replaced = true; break; }
                                                }
                                                if (!replaced && rObj.has("items") && rObj.get("items").isArray()) rObj.set("items", arr);
                                                rObj.put("returned", items.size());
                                                rObj.put("sample_of", sampleN2Used);
                                                ((com.fasterxml.jackson.databind.node.ObjectNode) riNode).set("result", rObj);
                                            }
                                        }
                                    }
                                }
                                to2 = new HashMap<>(to2);
                                to2.put("content", om.writeValueAsString(trimmedBatch));
                            } else {
                                JsonNode items = null;
                                for (String k : parametros.getAllowedTargetsPlural()) {
                                    if (cnode.has(k) && cnode.get(k).isArray()) { items = cnode.get(k); break; }
                                }
                                if (items == null && cnode.has("items") && cnode.get("items").isArray()) items = cnode.get("items");
                                if (items != null && items.size() > sampleN2Used) {
                                    com.fasterxml.jackson.databind.node.ObjectNode trimmed = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                                    com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                                    for (int i=0;i<sampleN2Used;i++) arr.add(projectItem(items.get(i)));
                                    boolean replaced = false;
                                    for (String k : parametros.getAllowedTargetsPlural()) {
                                        if (trimmed.has(k) && trimmed.get(k).isArray()) { trimmed.set(k, arr); replaced = true; break; }
                                    }
                                    if (!replaced && trimmed.has("items") && trimmed.get("items").isArray()) trimmed.set("items", arr);
                                    trimmed.put("returned", items.size());
                                    trimmed.put("sample_of", sampleN2Used);
                                    to2 = new HashMap<>(to2);
                                    to2.put("content", om.writeValueAsString(trimmed));
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                    try {
                        Object contentObj2 = to2.get("content");
                        String contentStr2 = contentObj2 == null ? null : String.valueOf(contentObj2);
                        if (contentStr2 != null) iterReinjectBytes += contentStr2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    } catch (Exception _ignore) {}
                    followup.add(to2);
                }
                // Nueva llamada a OpenAI con los nuevos tool outputs (inyectando instrucción de segundo turno)
                if (xmlLogger != null) {
                    xmlLogger.addStep("Telemetry", "[iter] api_ms_total=" + iterApiMs + ", reinject_payload_bytes=" + iterReinjectBytes);
                    xmlLogger.addStep("Telemetry", "[iter] echo_sample_used=" + sampleN2Used + ", large_list_context=" + largeListContext2);
                }
                long iterSecondStart = System.currentTimeMillis();
                try {
                    String miniResumenIter = buildMiniResumen(executed, sampleN2Used);
                    followup.add(java.util.Map.of("role","user","content", promptBuilder.buildSecondTurnInstruction(miniResumenIter)));
                } catch (Exception __ignoreIterInstr) {}
                String iterSecondRaw = null;
                try {
                    // FIX: Usar callChatWithToolsWithExtras para permitir flujo multi-paso (ej: GET /roles → POST /usuarios)
                    // El bucle está diseñado para iteraciones extras con tool_calls, pero necesita tools disponibles
                    iterSecondRaw = openai.callChatWithToolsWithExtras(followup, openai.buildChatResponseSchemaExtras(), xmlLogger, authorization);
                    second = (iterSecondRaw == null || iterSecondRaw.isBlank()) ? null : om.readTree(iterSecondRaw);
                } catch (Exception __iterSecondEx) {
                    if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Excepción en segunda llamada (iter): " + __iterSecondEx.getMessage());
                    second = null;
                }
                if (xmlLogger != null) xmlLogger.addStep("Telemetry", "[iter] second_ms=" + (System.currentTimeMillis() - iterSecondStart));
                if (debug) {
                    try {
                        String prettySecondIter = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(second);
                        System.out.println("[ChatService][DEBUG] OpenAI second response (iter " + (extraIters+1) + "):\n" + prettySecondIter);
                    } catch (Exception ignore) {}
                }
                if (second == null || !second.has("choices") || !second.get("choices").isArray() || second.get("choices").size() == 0) {
                    break; // salir del bucle; usaremos el contenido anterior/fallback
                }
                finalMsg = second.path("choices").get(0).path("message");
                extraIters++;
            }
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "decision_ms=" + (System.currentTimeMillis() - t0));
            if (debug) logger.debug("[Telemetry] decision_ms={} (since start)", (System.currentTimeMillis() - t0));
            String finalContent = (finalMsg == null || finalMsg.isMissingNode()) ? "" : finalMsg.path("content").asText("");
            // DEBUG CRÍTICO: Ver qué contiene finalContent
            if (xmlLogger != null) xmlLogger.addStep("DEBUG", "finalContent length=" + finalContent.length() + ", preview=" + (finalContent.length() > 100 ? finalContent.substring(0, 100) + "..." : finalContent));
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Respuesta final generada por OpenAI");
            if (debug) {
                try {
                    JsonNode tmpFinal = om.readTree(finalContent);
                    String prettyFinal = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tmpFinal);
                    System.out.println("[ChatService][DEBUG] Final content before returning (pretty):\n" + prettyFinal);
                } catch (Exception ignore) {
                    System.out.println("[ChatService][DEBUG] Final content before returning: " + finalContent);
                }
            }
            // Si finalContent es JSON válido, lo enriquecemos con items/paginación reales si procede
            try {
                JsonNode node = om.readTree(finalContent);
                JsonNode enriched;
                try {
                    enriched = coerceWithBackendResults(node, executed, incoming);
                } catch (Exception __coerce) {
                    // Fallback defensivo
                    enriched = maybeEnrichWithExecutedItems(node, executed);
                }
                ResponseEnvelope env = buildEnvelopeFromContentNode(enriched);
                // 1) Si falta total y hay paginación, intentar conteo lazy (opcional)
                try {
                    if (parametros.isLazyTotalEnabled() && env.getData() != null && env.getData().getPagination() != null) {
                        PaginationInfo p = env.getData().getPagination();
                        if (p.getTotal() == null) {
                            ExecMeta metaSel = selectExecMetaForPagination(executed, incoming);
                            Integer totalLazy = computeLazyTotal(openai, apiProxy, metaSel, p, authorization, null, xmlLogger);
                            if (totalLazy != null) env.getData().getPagination().setTotal(totalLazy);
                        }
                    }
                } catch (Exception ignore) {
                    // Fallback genérico: si algo falla durante el post-procesado, construir el envelope directamente
                    // a partir de los resultados reales del/los tool_call ejecutados.
                    ExecMeta metaSel = selectExecMetaForPagination(executed, incoming);
                    ResponseEnvelope envFallback = buildEnvelopeFromContentNode(metaSel == null ? om.createObjectNode() : metaSel.apiResultNode);
                    // Total lazy si falta
                    try {
                        if (envFallback.getData() != null && envFallback.getData().getPagination() != null) {
                            PaginationInfo p = envFallback.getData().getPagination();
                            if (p.getTotal() == null) {
                                Integer totalLazy = computeLazyTotal(openai, apiProxy, metaSel, p, authorization, null, xmlLogger);
                                if (totalLazy != null) envFallback.getData().getPagination().setTotal(totalLazy);
                            }
                        }
                    } catch (Exception __tl) { /* ignore */ }
                    // Componer mensaje con conteos
                    try { envFallback.setMessage(composePaginatedMessage(envFallback.getMessage(), envFallback.getData() == null ? null : envFallback.getData().getPagination())); } catch (Exception __m) { }
                    // Crear/enriquecer sugerencias y tokens
                    try { if (envFallback.getData() != null && envFallback.getData().getPagination() != null) ensurePaginationSuggestions(envFallback, envFallback.getData().getPagination(), metaSel); } catch (Exception __s) { }
                    if (xmlLogger != null) xmlLogger.addStep("ChatService", "Fallback aplicado: envelope construido desde resultados reales por contenido no-JSON del modelo.");
                    if (xmlLogger != null) xmlLogger.addStep("Telemetry", "render_ms=" + (System.currentTimeMillis() - t0));
                    if (debug) logger.debug("[Telemetry] render_ms={} (since start)", (System.currentTimeMillis() - t0));
                    return applyFinalFallback(envFallback);
                }
                // No modificar el mensaje del modelo ni auto-crear sugerencias; solo se inyectarán tokens si ya hay sugerencias con paginación
                if (xmlLogger != null) {
                    int itemsReturned = 0;
                    try { itemsReturned = env.getData() != null && env.getData().getItems() != null ? env.getData().getItems().size() : 0; } catch (Exception ignore) {}
                    xmlLogger.addStep("Telemetry", "summary seed_ms+api_ms+second_ms decision_ms=" + (System.currentTimeMillis() - t0) + ", items_returned=" + itemsReturned + ", calls_in_batch=" + totalCallsInBatch);
                }
                // Enriquecimiento ligero de presentación solo si está habilitado vía configuración
                if (parametros.isPresentationEnrichmentEnabled()) {
                    try { applyPresentationEnrichment(env); } catch (Exception ignore) {}
                }
                // Telemetría: render final
                if (xmlLogger != null) xmlLogger.addStep("Telemetry", "render_ms=" + (System.currentTimeMillis() - t0));
                if (debug) logger.debug("[Telemetry] render_ms={} (since start)", (System.currentTimeMillis() - t0));
                if (debug) {
                    try {
                        String prettyEnv = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(env);
                        System.out.println("[ChatService][DEBUG] Envelope(final_no_toolcalls):\n" + prettyEnv);
                    } catch (Exception ignore) {}
                }
                return applyFinalFallback(env);
            } catch (Exception e) {
                // Contenido final no es JSON válido: devolver error controlado (sin reconstrucción backend)
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Contenido final no JSON; devolviendo error controlado (orquestador puro)");
                if (xmlLogger != null) xmlLogger.addStep("Telemetry", "render_ms=" + (System.currentTimeMillis() - t0));
                if (debug) logger.debug("[Telemetry] render_ms={} (since start)", (System.currentTimeMillis() - t0));
                return applyFinalFallback(ResponseEnvelope.error("Error al procesar respuesta", "openai_parse_error", "Contenido final no JSON"));
            }

        } catch (Exception e) {
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Excepción en runChat: " + e.getMessage());
            if (debug) {
                System.out.println("[ChatService][DEBUG] Exception in runChat: " + e.getMessage());
                e.printStackTrace();
            }
            return ResponseEnvelope.error("Error en runChat","internal_error", e.getMessage());
        }
    }

    // Tratamiento de saludos unificado en el prompt; no se requiere refuerzo vía mensaje especial
    // Convención: incluir en el historial un assistant con contenido que empiece por "Saludo_refuerzo:" y contenga "true"
    // Ej.: { role: 'assistant', content: 'Saludo_refuerzo: true' }
    // Tratamiento de saludos unificado en el prompt; no se requiere refuerzo vía mensaje especial

    // Busca en el historial un mensaje (assistant o system) con prefijo "Paginacion_token: { ... }" y devuelve el JSON como ObjectNode
    private com.fasterxml.jackson.databind.node.ObjectNode extractPaginationToken(List<ChatRequest.Message> incoming) {
        if (incoming == null || incoming.isEmpty()) return null;
        String marker = "Paginacion_token:";
        for (int i = incoming.size() - 1; i >= 0; i--) {
            ChatRequest.Message m = incoming.get(i);
            try {
                if (m == null) continue;
                // Aceptamos el token proveniente de assistant (respuesta anterior) o system (mensaje auxiliar del cliente)
                String role = m.getRole();
                if (!"assistant".equalsIgnoreCase(role) && !"system".equalsIgnoreCase(role)) continue;
                String c = m.getContent();
                if (c == null) continue;
                int idx = c.indexOf(marker);
                if (idx >= 0) {
                    String json = c.substring(idx + marker.length()).trim();
                    if (json.startsWith("{")) {
                        JsonNode parsed = om.readTree(json);
                        if (parsed != null && parsed.isObject()) {
                            return (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
                        }
                    }
                }
            } catch (Exception ignore) { }
        }
        return null;
    }

    

    // (Eliminado) Lógica de detección de intención de agregación: el backend es un orquestador puro.

    // Modo LITE eliminado: no existe segundo turno específico

    // Heurística simple para inferir el tipo de recurso a partir de los campos presentes en los ítems
    private String guessTargetFromItems(List<JsonNode> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            // Recolecta conjunto de campos presentes en hasta N items
            Set<String> fields = new HashSet<>();
            int limit = Math.min(parametros.getHeuristicScanLimit(), items.size());
            for (int i = 0; i < limit; i++) {
                JsonNode it = items.get(i);
                if (it == null || !it.isObject()) continue;
                it.fieldNames().forEachRemaining(fields::add);
            }
            // Evalúa reglas ordenadas por prioridad
            List<ParametrosArbolDecision2CallOpenAI.HeuristicRule> rules = new ArrayList<>(parametros.getHeuristicRules());
            rules.sort(Comparator.comparingInt(ParametrosArbolDecision2CallOpenAI.HeuristicRule::getPriority));
            for (ParametrosArbolDecision2CallOpenAI.HeuristicRule r : rules) {
                boolean allOk = r.getAllOf().isEmpty() || fields.containsAll(r.getAllOf());
                boolean anyOk = r.getAnyOf().isEmpty() || r.getAnyOf().stream().anyMatch(fields::contains);
                if (allOk && anyOk) return r.getTarget();
            }
        } catch (Exception ignore) { }
        return null;
    }

    // Detecta errores comunes en la respuesta
    // Helper de error de respuesta eliminado (no usado)

    // Extrae el target por nombre de endpoint (último match)
    private String extractTargetFromEndpointName(String endpointName) {
        if (endpointName == null) return null;
        String e = endpointName.toLowerCase(Locale.ROOT);
        String found = null;
        for (String k : parametros.getAllowedTargetsPlural()) if (e.contains(k)) found = k;
        return found;
    }

    private static class ItemsAndPagination {
        List<JsonNode> items; // array plano
        com.fasterxml.jackson.databind.node.ObjectNode pagination; // puede ser null
        ItemsAndPagination(List<JsonNode> items, com.fasterxml.jackson.databind.node.ObjectNode pagination) { this.items = items; this.pagination = pagination; }
    }

    // Busca el array de items con prioridad: node[target] -> node[items] -> (fallbacks: node[result], node[results], node[data].items)
    private ItemsAndPagination findItemsArray(JsonNode node, String target) {
        if (node == null || node.isNull()) return null;
        JsonNode arr = null;
        if (target != null && node.has(target) && node.get(target).isArray()) arr = node.get(target);
        if (arr == null) {
            for (String gk : parametros.getGenericArrayKeys()) {
                if (node.has(gk) && node.get(gk).isArray()) { arr = node.get(gk); break; }
            }
        }
        if (arr == null && target != null) {
            List<String> tks = parametros.getTargetSpecificArrayKeys().getOrDefault(target, List.of());
            for (String tk : tks) {
                if (node.has(tk) && node.get(tk).isArray()) { arr = node.get(tk); break; }
            }
        }
        // Fallbacks genéricos muy comunes en APIs
        if (arr == null) {
            if (node.has("result") && node.get("result").isArray()) arr = node.get("result");
            else if (node.has("results") && node.get("results").isArray()) arr = node.get("results");
            else if (node.has("data") && node.get("data").isObject()) {
                JsonNode dn = node.get("data");
                if (dn.has("items") && dn.get("items").isArray()) arr = dn.get("items");
            }
        }
        if (arr == null || !arr.isArray()) return null;
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode it : arr) list.add(it);
        // Construir pagination si existen metadatos
        com.fasterxml.jackson.databind.node.ObjectNode pag = om.createObjectNode();
        boolean any = false;
        if (node.has("page")) { pag.set("page", node.get("page")); any = true; }
        if (node.has("size")) { pag.set("size", node.get("size")); any = true; }
        pag.put("returned", list.size()); any = true;
        if (node.has("has_more")) { pag.set("has_more", node.get("has_more")); any = true; }
        if (node.has("next_page")) { pag.set("next_page", node.get("next_page")); any = true; }
        if (node.has("prev_page")) { pag.set("prev_page", node.get("prev_page")); any = true; }
        if (node.has("total")) { pag.set("total", node.get("total")); any = true; }
        if (!any) pag = null;
        return new ItemsAndPagination(list, pag);
    }

    // Proyección compacta de ítems para eco: conserva solo campos clave si es un objeto
    private JsonNode projectItem(JsonNode original) {
        try {
            if (original == null || !original.isObject()) return original;
            com.fasterxml.jackson.databind.node.ObjectNode out = om.createObjectNode();
            // Selección de campos genéricos frecuentes
            String[] keys = new String[] {"id","nombre","email","estado","rol","academia_id"};
            for (String k : keys) {
                if (original.has(k)) out.set(k, original.get(k));
            }
            // Si no copiamos nada, devuelve original para no perder información crítica
            if (out.size() == 0) return original;
            return out;
        } catch (Exception ignore) { return original; }
    }

    // Eliminados helpers exclusivos del modo LITE (descriptor/truncado/merge)

    

    // tryPolishFastpathLite eliminado: mantenemos orquestación pura (el modelo redacta el texto)

    // Enforce conservative pagination defaults for paginated GET endpoints using whitelist metadata
    private JsonNode sanitizePagination(Map<String, Object> endpoint, String method, JsonNode query) {
        try {
            if (endpoint == null) return query;
            Object pag = endpoint.get("paginated");
            boolean isPaginated = (pag instanceof Boolean) ? (Boolean) pag : false;
            if (!isPaginated) return query;
            if (method == null || !"GET".equalsIgnoreCase(method)) return query;
            com.fasterxml.jackson.databind.node.ObjectNode q = (query == null || query.isNull()) ? om.createObjectNode() : (query.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode());
            int page = 1;
            try { if (q.has("page") && q.get("page").canConvertToInt()) page = Math.max(1, q.get("page").asInt()); } catch (Exception ignore) { }
            q.put("page", page);
            int size = 50;
            try { if (q.has("size") && q.get("size").canConvertToInt()) size = q.get("size").asInt(); } catch (Exception ignore) { }
            if (size <= 0) size = 50;
            if (size > 50) size = 50;
            q.put("size", size);
            return q;
        } catch (Exception e) {
            return query;
        }
    }
    
    // Fallback final: no altera el texto ni genera sugerencias; la IA es la responsable del copy
    private ResponseEnvelope applyFinalFallback(ResponseEnvelope env) {
        try {
            if (env != null && "success".equals(env.getStatus())) {
                boolean noItems = (env.getData() == null) || (env.getData().getItems() == null) || env.getData().getItems().isEmpty();
                // Si hay items pero el tipo es "chat", intenta inferir un tipo más específico (usuarios, cursos, etc.)
                try {
                    if (!noItems && env.getData() != null) {
                        String t = env.getData().getType();
                        if (t == null || t.isBlank() || "chat".equalsIgnoreCase(t)) {
                            String guessed = guessTargetFromItems(env.getData().getItems());
                            if (guessed != null) env.getData().setType(guessed);
                        }
                    }
                } catch (Exception ignore) { }
                // No modificar message en blanco; el prompt debe garantizar un texto adecuado
            }
        } catch (Exception ignore) { }
        return env;
    }

    // Asegura que, si el modelo devolvió arrays truncados por el eco (sample) o sin 'pagination',
    // la salida final usa los items y metadatos reales del último tool_call ejecutado.
    private JsonNode coerceWithBackendResults(JsonNode modelNode, List<ExecMeta> executed, List<ChatRequest.Message> incoming) {
        try {
            if (modelNode == null || !modelNode.isObject()) return modelNode;
            if (executed == null || executed.isEmpty()) return modelNode;
            // Seleccionar ejecución según contexto de navegación si existe
            ExecMeta meta = selectExecMetaForPagination(executed, incoming);
            ItemsAndPagination ip = findItemsArray(meta.apiResultNode, extractTargetFromEndpointName(meta.endpointName));
            // Fallback: si la selección no contiene items (p. ej., batch con llamadas auxiliares), elegir la primera ejecución con items reales empezando por la última
            if (ip == null || ip.items == null || ip.items.isEmpty()) {
                for (int i = executed.size() - 1; i >= 0; i--) {
                    ExecMeta cand = executed.get(i);
                    ItemsAndPagination ipCand = findItemsArray(cand.apiResultNode, extractTargetFromEndpointName(cand.endpointName));
                    if (ipCand != null && ipCand.items != null && !ipCand.items.isEmpty()) {
                        meta = cand;
                        ip = ipCand;
                        break;
                    }
                }
            }
            if (ip == null || ip.items == null || ip.items.isEmpty()) return modelNode;
            // Detecta si el modelo ya trae un array grande y coherente; si no, sobrescribe con los reales
            String knownKey = null;
            for (String k : parametros.getAllowedTargetsPlural()) {
                JsonNode arr = modelNode.get(k);
                if (arr != null && arr.isArray()) { knownKey = k; break; }
            }
            com.fasterxml.jackson.databind.node.ObjectNode out = modelNode.deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode arrReal = om.createArrayNode();
            for (JsonNode it : ip.items) arrReal.add(it);
            String target = (knownKey != null) ? knownKey : extractTargetFromEndpointName(meta.endpointName);
            if (target == null) target = guessTargetFromItems(ip.items);
            if (target == null) target = "items";
            out.set(target, arrReal);
            // Completar/inyectar metadatos de paginación fiables
            if (ip.pagination != null) {
                // Completar con page/size desde querySnapshot si faltan
                com.fasterxml.jackson.databind.node.ObjectNode pag = ip.pagination.deepCopy();
                try {
                    if (!pag.has("page") && meta.querySnapshot != null && meta.querySnapshot.has("page") && meta.querySnapshot.get("page").canConvertToInt()) {
                        pag.put("page", meta.querySnapshot.get("page").asInt());
                    }
                    if (!pag.has("size") && meta.querySnapshot != null && meta.querySnapshot.has("size") && meta.querySnapshot.get("size").canConvertToInt()) {
                        pag.put("size", meta.querySnapshot.get("size").asInt());
                    }
                    // Si no hay 'has_more', inferirlo de returned y size
                    if (!pag.has("has_more") && pag.has("returned") && pag.has("size") && pag.get("returned").canConvertToInt() && pag.get("size").canConvertToInt()) {
                        boolean hasMore = pag.get("returned").asInt() >= pag.get("size").asInt();
                        pag.put("has_more", hasMore);
                    }
                } catch (Exception ignore) { }
                out.set("pagination", pag);
            }
            return out;
        } catch (Exception ignore) { return modelNode; }
    }

    // Selecciona la ejecución a usar para paginación en base al historial; por defecto, la última
    private ExecMeta selectExecMetaForPagination(List<ExecMeta> executed, List<ChatRequest.Message> incoming) {
        ExecMeta meta = executed.get(executed.size() - 1);
        try {
            NavContext nav = extractNavContext(incoming);
            if (nav != null) {
                int desired = "next".equals(nav.direction) ? nav.currentPage + 1 : Math.max(1, nav.currentPage - 1);
                for (int i = executed.size() - 1; i >= 0; i--) {
                    ExecMeta cand = executed.get(i);
                    JsonNode q = cand.querySnapshot;
                    if (q != null && q.has("page") && q.get("page").canConvertToInt()) {
                        if (q.get("page").asInt() == desired) { meta = cand; break; }
                    }
                }
            }
        } catch (Exception ignore) { }
        return meta;
    }

    // Elimina page/size de una query para firmar solo filtros/sort en el token
    private JsonNode stripPageSizeFromQuery(JsonNode q) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode out = (q == null || q.isNull()) ? om.createObjectNode() : (com.fasterxml.jackson.databind.node.ObjectNode) q.deepCopy();
            out.remove("page");
            out.remove("size");
            return out;
        } catch (Exception ignore) { return om.createObjectNode(); }
    }

    // Cómputo lazy del total cuando la API no lo da. Usa estrategia exponencial + binaria con límites.
    private Integer computeLazyTotal(OpenAICallApiService openaiSvc, ApiProxyService apiSvc, ExecMeta base, PaginationInfo pag, String authorization, String delegatedAuth, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) {
        try {
            if (base == null || pag == null) return null;
            Integer page = pag.getPage();
            Integer size = pag.getSize();
            Integer returned = pag.getReturned();
            Boolean hasMore = pag.getHasMore();
            if (page == null || size == null || returned == null) return null;
            // Si ya estamos en última página, total inmediato
            if (hasMore != null && !hasMore) {
                return Math.max(0, (page - 1) * size + returned);
            }
            Map<String,Object> ep = openaiSvc.getEndpointByName(base.endpointName);
            if (ep == null) return null;
            // Helpers locales
            java.util.function.Function<Integer, ItemsAndPagination> fetchPage = (Integer targetPage) -> {
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode q = (base.querySnapshot == null || base.querySnapshot.isNull()) ? om.createObjectNode() : (com.fasterxml.jackson.databind.node.ObjectNode) base.querySnapshot.deepCopy();
                    q.put("page", Math.max(1, targetPage));
                    q.put("size", size);
                    String authToUse = (delegatedAuth != null) ? delegatedAuth : authorization;
                    String res = (xmlLogger != null)
                            ? apiSvc.executeSpecCall(ep, base.method, base.pathParamsSnapshot, q, null, authToUse, xmlLogger)
                            : apiSvc.executeSpecCall(ep, base.method, base.pathParamsSnapshot, q, null, authToUse);
                    JsonNode node = om.readTree(res);
                    return findItemsArray(node, extractTargetFromEndpointName(base.endpointName));
                } catch (Exception ex) { return null; }
            };
            // Exponencial: duplicar hasta encontrar una página sin más
            int lo = page;
            int hi = page;
            int maxExp = 6; // ~64x
            ItemsAndPagination ipHi = null;
            for (int i=0;i<maxExp;i++) {
                hi = (i==0) ? Math.max(page+1, 2) : (hi * 2);
                ipHi = fetchPage.apply(hi);
                if (ipHi == null) break;
                boolean hm = false;
                if (ipHi.pagination != null && ipHi.pagination.has("has_more") && ipHi.pagination.get("has_more").isBoolean()) hm = ipHi.pagination.get("has_more").asBoolean();
                int ret = (ipHi.items == null) ? 0 : ipHi.items.size();
                if (!hm || ret < size) {
                    break; // hi está en la última o pasada
                } else {
                    lo = hi; // todavía hay más
                }
            }
            // Binaria entre lo..hi
            int left = lo;
            int right = hi;
            ItemsAndPagination ipLast = null;
            int maxBin = 8;
            while (left <= right && maxBin-- > 0) {
                int mid = left + (right - left)/2;
                ItemsAndPagination ip = fetchPage.apply(mid);
                if (ip == null) break;
                boolean hm = false;
                if (ip.pagination != null && ip.pagination.has("has_more") && ip.pagination.get("has_more").isBoolean()) hm = ip.pagination.get("has_more").asBoolean();
                int ret = (ip.items == null) ? 0 : ip.items.size();
                if (!hm || ret < size) {
                    ipLast = ip; // candidato a última
                    right = mid - 1;
                } else {
                    left = mid + 1;
                }
            }
            if (ipLast != null) {
                int lastPage = (ipLast.pagination != null && ipLast.pagination.has("page") && ipLast.pagination.get("page").canConvertToInt())
                    ? ipLast.pagination.get("page").asInt()
                    : left; // mejor esfuerzo
                int lastReturned = (ipLast.items == null) ? 0 : ipLast.items.size();
                return Math.max(0, (lastPage - 1) * size + lastReturned);
            }
            return null;
        } catch (Exception ignore) { return null; }
    }

    // Crear auto-sugerencias Prev/Sig si faltan y enriquecerlas con token ampliado
    private void ensurePaginationSuggestions(ResponseEnvelope env, PaginationInfo pagination, ExecMeta meta) {
        if (env == null || pagination == null) return;
        if (env.getUiSuggestions() == null) env.setUiSuggestions(new java.util.ArrayList<>());
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Suggestion s : env.getUiSuggestions()) {
            if (s == null) continue;
            String t = s.getType() == null ? "" : s.getType();
            String dt = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
            if ("Paginacion".equalsIgnoreCase(t)) {
                if (dt.contains("siguiente") || dt.contains("next")) have.add("next");
                if (dt.contains("anterior") || dt.contains("prev")) have.add("prev");
            }
        }
        java.util.List<Suggestion> list = new java.util.ArrayList<>();
        // Filtrar sugerencias inválidas aportadas por el modelo
        Integer cur = pagination.getPage();
        Integer size = pagination.getSize();
        Boolean hasMore = pagination.getHasMore();
        // Paso 1: separar no-paginación y candidatas de paginación (validando límites)
        java.util.List<Suggestion> nonPag = new java.util.ArrayList<>();
        java.util.List<Suggestion> pagCandidates = new java.util.ArrayList<>();
        for (Suggestion s : env.getUiSuggestions()) {
            if (s == null) continue;
            if (!"Paginacion".equalsIgnoreCase(s.getType())) { nonPag.add(s); continue; }
            String dt = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
            boolean isPrev = dt.contains("anterior") || dt.contains("prev");
            boolean isNext = dt.contains("siguiente") || dt.contains("next");
            if (isPrev && cur != null && cur <= 1) {
                // descartar 'Anterior' en primera página
                continue;
            }
            if (isNext && hasMore != null && !hasMore) {
                // descartar 'Siguiente' si no hay más
                continue;
            }
            pagCandidates.add(s);
        }
        // Paso 2: deduplicar paginación dejando como mucho un 'next' y un 'prev'
        Suggestion firstNext = null;
        Suggestion firstPrev = null;
        for (Suggestion s : pagCandidates) {
            String dir = null;
            if (s.getPagination() != null && s.getPagination().getDirection() != null) {
                dir = s.getPagination().getDirection().toLowerCase();
            } else {
                String dt = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
                if (dt.contains("siguiente") || dt.contains("next")) dir = "next"; else if (dt.contains("anterior") || dt.contains("prev")) dir = "prev";
            }
            if ("next".equals(dir)) {
                if (firstNext == null) firstNext = s; // conservar la primera válida
                continue; // extra 'next' se descartan
            }
            if ("prev".equals(dir)) {
                if (firstPrev == null) firstPrev = s;
                continue;
            }
        }
        // Paso 3: construir lista final empezando por no-paginación y añadiendo como mucho un prev/next canónico
        list.addAll(nonPag);
        if (firstPrev != null) {
            // Canonicalizar id/display y normalizar metadatos
            firstPrev.setId("pg-prev");
            firstPrev.setDisplayText("Anterior");
            if (firstPrev.getPagination() == null) firstPrev.setPagination(new Suggestion.PaginationSuggestion());
            firstPrev.getPagination().setDirection("prev");
            if (cur != null && size != null) {
                if (firstPrev.getPagination().getPage() == null) firstPrev.getPagination().setPage(Math.max(1, cur - 1));
                if (firstPrev.getPagination().getSize() == null) firstPrev.getPagination().setSize(size);
            }
            list.add(firstPrev);
        }
        if (firstNext != null) {
            firstNext.setId("pg-next");
            firstNext.setDisplayText("Siguiente");
            if (firstNext.getPagination() == null) firstNext.setPagination(new Suggestion.PaginationSuggestion());
            firstNext.getPagination().setDirection("next");
            if (cur != null && size != null) {
                if (firstNext.getPagination().getPage() == null) firstNext.getPagination().setPage(cur + 1);
                if (firstNext.getPagination().getSize() == null) firstNext.getPagination().setSize(size);
            }
            list.add(firstNext);
        }
        // Nota: las ausencias se cubrirán en el bloque posterior que añade faltantes
        // Recalcular 'have' tras filtro
        have.clear();
        for (Suggestion s : list) {
            if (s == null) continue;
            String t = s.getType() == null ? "" : s.getType();
            String dt2 = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
            if ("Paginacion".equalsIgnoreCase(t)) {
                if (dt2.contains("siguiente") || dt2.contains("next")) have.add("next");
                if (dt2.contains("anterior") || dt2.contains("prev")) have.add("prev");
            }
        }
        if (cur != null && size != null) {
            if ((hasMore != null && hasMore) && !have.contains("next")) {
                Suggestion s = new Suggestion();
                s.setId("pg-next"); s.setDisplayText("Siguiente"); s.setType("Paginacion");
                s.setPagination(new Suggestion.PaginationSuggestion("next", cur+1, size));
                list.add(s);
            }
            if (cur > 1 && !have.contains("prev")) {
                Suggestion s = new Suggestion();
                s.setId("pg-prev"); s.setDisplayText("Anterior"); s.setType("Paginacion");
                s.setPagination(new Suggestion.PaginationSuggestion("prev", cur-1, size));
                list.add(s);
            }
        }
        env.setUiSuggestions(list);
        // Asegurar versión de sugerencias coherente cuando existan uiSuggestions
        try { if (env.getUiSuggestions() != null && !env.getUiSuggestions().isEmpty()) env.setUiSuggestionsVersion(1); } catch (Exception __v) {}
        // Enriquecer con token ampliado
        try { enrichPaginationSuggestionsWithTokensExpanded(env, env.getData() == null ? null : env.getData().getType(), pagination, meta); } catch (Exception ignore) {}
    }

    // Versión expandida: añade endpoint, path, method, filtros/baseQuery y total al token
    private void enrichPaginationSuggestionsWithTokensExpanded(ResponseEnvelope env, String type, PaginationInfo pag, ExecMeta meta) {
        if (env == null || env.getUiSuggestions() == null || pag == null) return;
        Integer cur = pag.getPage(); Integer size = pag.getSize(); Integer next = pag.getNextPage(); Integer prev = pag.getPrevPage(); Boolean hasMore = pag.getHasMore();
        if (cur == null || size == null) return;
        JsonNode baseQuery = stripPageSizeFromQuery(meta == null ? null : meta.querySnapshot);
        for (Suggestion s : env.getUiSuggestions()) {
            if (s == null || !"Paginacion".equalsIgnoreCase(s.getType())) continue;
            if (s.getContextToken() != null && !s.getContextToken().isBlank()) continue;
            String direction = null; Integer targetPage = null;
            if (s.getPagination() != null && s.getPagination().getDirection() != null) {
                String dir = s.getPagination().getDirection().toLowerCase();
                if ("next".equals(dir)) { direction = "next"; targetPage = (next != null) ? next : (hasMore != null && hasMore ? cur + 1 : null); }
                if ("prev".equals(dir) || "previous".equals(dir)) { direction = "prev"; targetPage = (prev != null) ? prev : (cur > 1 ? cur - 1 : null); }
            }
            if (direction == null) {
                String text = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
                if (text.contains("siguiente") || text.contains("next")) { direction = "next"; targetPage = (next != null) ? next : (hasMore != null && hasMore ? cur + 1 : null); }
                else if (text.contains("anterior") || text.contains("prev")) { direction = "prev"; targetPage = (prev != null) ? prev : (cur > 1 ? cur - 1 : null); }
            }
            if (direction != null && targetPage != null) {
                java.util.Map<String,Object> payload = new java.util.HashMap<>();
                payload.put("type", type);
                payload.put("page", cur);
                payload.put("size", size);
                payload.put("target_page", targetPage);
                if (meta != null) {
                    payload.put("endpoint_name", meta.endpointName);
                    payload.put("method", meta.method);
                    payload.put("endpoint_path", meta.endpointPath);
                    payload.put("filters", baseQuery == null ? new java.util.HashMap<>() : om.convertValue(baseQuery, java.util.Map.class));
                    if (meta.pathParamsSnapshot != null) payload.put("path_params", om.convertValue(meta.pathParamsSnapshot, java.util.Map.class));
                }
                if (pag.getTotal() != null) payload.put("total", pag.getTotal());
                String token = contextTokenService.sign(payload);
                s.setContextToken(token);
                // Asegurar coherencia en los metadatos de la sugerencia (direction/page/size)
                if (s.getPagination() == null) {
                    s.setPagination(new Suggestion.PaginationSuggestion(direction, targetPage, size));
                } else {
                    s.getPagination().setDirection(direction);
                    s.getPagination().setPage(targetPage);
                    s.getPagination().setSize(size);
                }
            }
        }
    }

    private String composePaginatedMessage(String original, PaginationInfo p) {
        try {
            String base = (original == null) ? "" : original.trim();
            if (p == null) return base;
            Integer page = p.getPage(); Integer returned = p.getReturned(); Integer total = p.getTotal();
            String suffix;
            if (total != null && total >= 0) suffix = " (" + (returned == null ? 0 : returned) + " de " + total + ")";
            else suffix = " (" + (returned == null ? 0 : returned) + " en esta página)";
            if (base.isEmpty()) {
                return (page != null ? ("Mostrando página " + page + ".") : "Listado.") + suffix;
            }
            return base + suffix;
        } catch (Exception ignore) { return original; }
    }

    // Enriquecimiento ligero de presentación: renombra claves de conteo a labels amigables y rellena summary_fields si faltan
    private void applyPresentationEnrichment(ResponseEnvelope env) {
        if (env == null || env.getData() == null) return;
        DataSection data = env.getData();
        // 1) Relleno de summary_fields si no viene del modelo
        if ((data.getSummaryFields() == null || data.getSummaryFields().isEmpty()) && data.getItems() != null && !data.getItems().isEmpty()) {
            try {
                JsonNode first = data.getItems().get(0);
                java.util.List<String> sfs = new java.util.ArrayList<>();
                if (first.has("nombre")) sfs.add("nombre");
                if (first.has("email")) sfs.add("email");
                if (sfs.isEmpty() && first.has("id")) sfs.add("id");
                if (!sfs.isEmpty()) data.setSummaryFields(sfs);
            } catch (Exception ignore) {}
        }
        // 2) Alias de *_count a etiqueta más amigable (no cambiamos la clave, añadimos display_label si procede en messages)
        // Mantener datos tal cual, pero podemos añadir un mensaje informativo si detectamos campos *_count
        if (data.getItems() != null && !data.getItems().isEmpty()) {
            try {
                JsonNode first = data.getItems().get(0);
                java.util.Iterator<String> it = first.fieldNames();
                boolean hasCount = false;
                while (it.hasNext()) {
                    String fn = it.next();
                    if (fn.endsWith("_count")) { hasCount = true; break; }
                }
                if (hasCount) {
                    // Añadimos una nota sutil en messages para que el cliente pueda mostrar un label mejor
                    java.util.List<MessageEntry> msgs = env.getMessages() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(env.getMessages());
                    msgs.add(MessageEntry.of("hint","Campos *_count representan conteos (p.ej., 'usuarios_count' = 'número de usuarios')."));
                    env.setMessages(msgs);
                }
            } catch (Exception ignore) {}
        }
    }
    // Nuevo método privado para construir Envelope desde un JsonNode del modelo
    private ResponseEnvelope buildEnvelopeFromContentNode(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return ResponseEnvelope.success("", DataSection.of("chat", List.of(), null), List.of(), List.of());
        }
        // Detect arrays representando recursos
    List<String> resourceKeys = parametros.getAllowedTargetsPlural();
    String typeDetected = parametros.getDefaultType();
        List<JsonNode> items = new ArrayList<>();
        PaginationInfo pagination = null;
        for (String k : resourceKeys) {
            JsonNode arr = contentNode.get(k);
            if (arr != null && arr.isArray()) {
                typeDetected = k;
                for (JsonNode el : arr) items.add(el);
                // pagination detection
                JsonNode pagNode = contentNode.get("pagination");
                if (pagNode != null && pagNode.isObject()) {
                    pagination = PaginationInfo.of(
                        pagNode.path("page").isNumber()? pagNode.get("page").asInt() : null,
                        pagNode.path("size").isNumber()? pagNode.get("size").asInt() : null,
                        pagNode.path("returned").isNumber()? pagNode.get("returned").asInt() : (arr.size()),
                        pagNode.path("has_more").isBoolean()? pagNode.get("has_more").asBoolean() : null,
                        pagNode.path("next_page").isNumber()? pagNode.get("next_page").asInt() : null,
                        pagNode.path("prev_page").isNumber()? pagNode.get("prev_page").asInt() : null,
                        pagNode.path("total").isNumber()? pagNode.get("total").asInt() : null
                    );
                }
                break; // primero encontrado
            }
        }
        // Fallback: si no se ha detectado una clave conocida pero existe 'items' como array, intentar inferir el tipo por el contenido
        if (items.isEmpty()) {
            JsonNode genericArr = null;
            for (String gk : parametros.getGenericArrayKeys()) {
                JsonNode candidate = contentNode.get(gk);
                if (candidate != null && candidate.isArray()) { genericArr = candidate; break; }
            }
            if (genericArr != null) {
                List<JsonNode> tmp = new ArrayList<>();
                for (JsonNode el : genericArr) tmp.add(el);
                String guessed = guessTargetFromItems(tmp);
                if (guessed != null) {
                    typeDetected = guessed;
                    items.addAll(tmp);
                    JsonNode pagNode = contentNode.get("pagination");
                    if (pagNode != null && pagNode.isObject()) {
                        pagination = PaginationInfo.of(
                            pagNode.path("page").isNumber()? pagNode.get("page").asInt() : null,
                            pagNode.path("size").isNumber()? pagNode.get("size").asInt() : null,
                            pagNode.path("returned").isNumber()? pagNode.get("returned").asInt() : (genericArr.size()),
                            pagNode.path("has_more").isBoolean()? pagNode.get("has_more").asBoolean() : null,
                            pagNode.path("next_page").isNumber()? pagNode.get("next_page").asInt() : null,
                            pagNode.path("prev_page").isNumber()? pagNode.get("prev_page").asInt() : null,
                            pagNode.path("total").isNumber()? pagNode.get("total").asInt() : null
                        );
                    }
                }
            }
        }
        // Si no encontró array de recursos pero el nodo es objeto => intentar detectar clave singular y mapear a lista con un único ítem
        if (items.isEmpty() && contentNode.isObject()) {
            // Soportar claves singulares habituales devueltas por la IA (o por reformateo)
            for (Map.Entry<String, String> entry : parametros.getSingularToPlural().entrySet()) {
                JsonNode obj = contentNode.get(entry.getKey());
                if (obj != null && obj.isObject()) {
                    typeDetected = entry.getValue();
                    items.add(obj);
                    break;
                }
            }
            // Si tiene únicamente 'text', usar solo mensaje (sin datos)
            if (items.isEmpty() && contentNode.has("text") && contentNode.size() == 1) {
                String msg = contentNode.get("text").asText("");
                return ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of());
            }
        }
        // Tomar 'text' exactamente como venga de la IA; si no viene, dejar vacío
        String message = contentNode.has("text") && contentNode.get("text").isTextual() ? contentNode.get("text").asText("") : "";
        DataSection data = DataSection.of(typeDetected, items, pagination);
        // Map optional summary_fields -> data.summaryFields only when items exist
        try {
            JsonNode sf = contentNode.get("summary_fields");
            if (sf != null && sf.isArray() && !items.isEmpty()) {
                List<String> sfl = new ArrayList<>();
                for (JsonNode sfi : sf) if (sfi.isTextual()) sfl.add(sfi.asText());
                if (!sfl.isEmpty()) data.setSummaryFields(sfl);
            }
        } catch (Exception ignore) {}
    // Mapear opcionalmente ui_suggestions del primer turno si vienen en el JSON del modelo
    java.util.List<com.workers.profesores.chat.dto.response.Suggestion> uiFirst = new java.util.ArrayList<>();
    try {
        JsonNode uiNode = contentNode.get("ui_suggestions");
        if (uiNode != null && uiNode.isArray()) {
            for (int i = 0; i < uiNode.size(); i++) {
                JsonNode it = uiNode.get(i);
                com.workers.profesores.chat.dto.response.Suggestion s = new com.workers.profesores.chat.dto.response.Suggestion();
                if (it.has("id") && it.get("id").isTextual()) s.setId(it.get("id").asText()); else s.setId("sg-" + (i+1));
                // Aceptar tanto 'display_text' como 'displayText' (robustez)
                if (it.has("display_text") && it.get("display_text").isTextual()) {
                    s.setDisplayText(it.get("display_text").asText());
                } else if (it.has("displayText") && it.get("displayText").isTextual()) {
                    s.setDisplayText(it.get("displayText").asText());
                }
                if (it.has("type") && it.get("type").isTextual()) s.setType(it.get("type").asText());
                if (it.has("recordAction") && it.get("recordAction").isTextual()) s.setRecordAction(it.get("recordAction").asText());
                // Mapear subobjeto 'pagination' si viene del modelo (el modelo no debe aportar contextToken)
                try {
                    JsonNode pagNode2 = it.get("pagination");
                    if (pagNode2 != null && pagNode2.isObject()) {
                        String dir2 = pagNode2.has("direction") && pagNode2.get("direction").isTextual() ? pagNode2.get("direction").asText() : null;
                        Integer pg2 = pagNode2.has("page") && pagNode2.get("page").canConvertToInt() ? pagNode2.get("page").asInt() : null;
                        Integer sz2 = pagNode2.has("size") && pagNode2.get("size").canConvertToInt() ? pagNode2.get("size").asInt() : null;
                        if (dir2 != null || pg2 != null || sz2 != null) {
                            com.workers.profesores.chat.dto.response.Suggestion.PaginationSuggestion ps2 = new com.workers.profesores.chat.dto.response.Suggestion.PaginationSuggestion();
                            if (dir2 != null) ps2.setDirection(dir2);
                            if (pg2 != null) ps2.setPage(pg2);
                            if (sz2 != null) ps2.setSize(sz2);
                            s.setPagination(ps2);
                        }
                    }
                } catch (Exception __mapPag) { /* ignore bad shape */ }
                uiFirst.add(s);
            }
        }
    } catch (Exception ignore) {}
    // Validación estructural y canonización determinista de ui_suggestions (sin inventar)
    java.util.List<com.workers.profesores.chat.dto.response.Suggestion> uiNormalized = normalizeUiSuggestions(uiFirst, data.getPagination());
    ResponseEnvelope env = ResponseEnvelope.success(message, data, uiNormalized, List.of());
    env.setUiSuggestionsVersion(1);
    // Enriquecer sugerencias de paginación del primer turno con tokens si faltan
    try { enrichPaginationSuggestionsWithTokens(env, typeDetected, pagination); } catch (Exception ignore) {}
        if (debug) {
            try {
                String prettyEnv = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(env);
                System.out.println("[ChatService][DEBUG] buildEnvelopeFromContentNode(type=" + typeDetected + ", items=" + items.size() + ") =>\n" + prettyEnv);
            } catch (Exception ignore) {}
        }
        return env;
    }

    // Endurecer/clarificar: valida forma y canoniza sugerencias de UI (id/display_text/type obligatorios; Paginacion con metadatos correctos; deduplicación)
    private java.util.List<com.workers.profesores.chat.dto.response.Suggestion> normalizeUiSuggestions(
            java.util.List<com.workers.profesores.chat.dto.response.Suggestion> in,
            PaginationInfo pagination
    ) {
        if (in == null || in.isEmpty()) return in;
        java.util.List<com.workers.profesores.chat.dto.response.Suggestion> nonPag = new java.util.ArrayList<>();
        java.util.List<com.workers.profesores.chat.dto.response.Suggestion> pagCandidates = new java.util.ArrayList<>();
        Integer cur = pagination == null ? null : pagination.getPage();
        Integer size = pagination == null ? null : pagination.getSize();
        Boolean hasMore = pagination == null ? null : pagination.getHasMore();

        // Helper: canonicaliza el tipo a 'Paginacion'|'Registro'|'Generica'
        java.util.function.Function<String,String> canonType = (t) -> {
            if (t == null) return null;
            String tl = t.trim().toLowerCase(Locale.ROOT);
            if ("paginacion".equals(tl) || "paginación".equals(tl)) return "Paginacion";
            if ("registro".equals(tl)) return "Registro";
            if ("generica".equals(tl) || "genérica".equals(tl)) return "Generica";
            return null; // tipo no válido
        };
        java.util.Set<String> allowedRecordActions = java.util.Set.of("Alta","Baja","Modificacion","Consulta");

        for (com.workers.profesores.chat.dto.response.Suggestion s : in) {
            if (s == null) continue;
            String id = s.getId();
            String dt = s.getDisplayText();
            String type = canonType.apply(s.getType());
            if (type == null) continue; // tipo inválido
            // Reasignar tipo canonicalizado
            s.setType(type);
            // Validación básica de id/display_text
            if (id == null || id.isBlank()) continue;
            if (dt == null || dt.isBlank()) {
                // Para Paginacion podemos establecer un display estándar según dirección luego; para otros tipos se descarta
                if (!"Paginacion".equals(type)) continue;
            }
            if ("Registro".equals(type)) {
                if (s.getRecordAction() == null || !allowedRecordActions.contains(s.getRecordAction())) {
                    continue; // inválido sin recordAction correcto
                }
                nonPag.add(s);
                continue;
            }
            if ("Generica".equals(type)) {
                nonPag.add(s);
                continue;
            }
            // Paginacion: validar y acumular para canonización posterior
            if ("Paginacion".equals(type)) {
                // Si no hay subobjeto pagination, crearlo para poder completar
                if (s.getPagination() == null) s.setPagination(new com.workers.profesores.chat.dto.response.Suggestion.PaginationSuggestion());
                // Inferir dirección por metadata o por displayText
                String dir = s.getPagination().getDirection();
                if (dir == null || dir.isBlank()) {
                    String ldt = dt == null ? "" : dt.toLowerCase(Locale.ROOT);
                    if (ldt.contains("siguiente") || ldt.contains("next")) dir = "next";
                    else if (ldt.contains("anterior") || ldt.contains("prev")) dir = "prev";
                }
                if (dir == null) continue; // sin dirección no es válida
                dir = dir.toLowerCase(Locale.ROOT);
                if (!dir.equals("next") && !dir.equals("prev")) continue;
                s.getPagination().setDirection(dir);
                // Reglas de viabilidad: descartar 'prev' en primera página; descartar 'next' si hasMore=false
                if ("prev".equals(dir) && cur != null && cur <= 1) continue;
                if ("next".equals(dir) && hasMore != null && !hasMore) continue;
                // Completar page/size si faltan con datos fiables del backend
                if (cur != null && s.getPagination().getPage() == null) {
                    s.getPagination().setPage("next".equals(dir) ? cur + 1 : Math.max(1, cur - 1));
                }
                if (size != null && s.getPagination().getSize() == null) {
                    s.getPagination().setSize(size);
                }
                // Canonicalizar id/display_text
                if ("prev".equals(dir)) { s.setId("pg-prev"); s.setDisplayText("Anterior"); }
                if ("next".equals(dir)) { s.setId("pg-next"); s.setDisplayText("Siguiente"); }
                pagCandidates.add(s);
            }
        }

        // Deduplicar: conservar como máximo un prev y un next
        com.workers.profesores.chat.dto.response.Suggestion firstPrev = null;
        com.workers.profesores.chat.dto.response.Suggestion firstNext = null;
        for (com.workers.profesores.chat.dto.response.Suggestion s : pagCandidates) {
            String d = s.getPagination() == null ? null : s.getPagination().getDirection();
            if (d == null) continue;
            if ("prev".equalsIgnoreCase(d)) { if (firstPrev == null) firstPrev = s; }
            else if ("next".equalsIgnoreCase(d)) { if (firstNext == null) firstNext = s; }
        }
        java.util.List<com.workers.profesores.chat.dto.response.Suggestion> out = new java.util.ArrayList<>();
        out.addAll(nonPag);
        if (firstPrev != null) out.add(firstPrev);
        if (firstNext != null) out.add(firstNext);
        return out;
    }

    // Construye un mini-resumen compacto (2 líneas) con claves de paginación y muestra
    private String buildMiniResumen(List<ExecMeta> executed, Integer sampleOf) {
        try {
            String tipo = null; Integer page=null, size=null, next=null, prev=null, returned=null; Boolean hasMore=null;
            if (executed != null && !executed.isEmpty()) {
                // Selecciona la ejecución con items más representativa (última con items; si no, la última)
                ExecMeta chosen = null; ItemsAndPagination ipChosen = null;
                for (int i = executed.size()-1; i >= 0; i--) {
                    ExecMeta em = executed.get(i);
                    if (em == null || em.apiResultNode == null) continue;
                    String t = extractTargetFromEndpointName(em.endpointName);
                    ItemsAndPagination ip = findItemsArray(em.apiResultNode, t);
                    if (ip != null && ip.items != null && !ip.items.isEmpty()) { chosen = em; ipChosen = ip; break; }
                }
                if (chosen == null) { chosen = executed.get(executed.size()-1); }
                tipo = extractTargetFromEndpointName(chosen.endpointName);
                ItemsAndPagination ip = (ipChosen != null) ? ipChosen : findItemsArray(chosen.apiResultNode, tipo);
                if (ip != null) {
                    returned = (ip.items == null) ? 0 : ip.items.size();
                    JsonNode p = ip.pagination;
                    if (p != null && p.isObject()) {
                        if (p.has("page") && p.get("page").canConvertToInt()) page = p.get("page").asInt();
                        if (p.has("size") && p.get("size").canConvertToInt()) size = p.get("size").asInt();
                        if (p.has("has_more") && p.get("has_more").isBoolean()) hasMore = p.get("has_more").asBoolean();
                        if (p.has("next_page") && p.get("next_page").canConvertToInt()) next = p.get("next_page").asInt();
                        if (p.has("prev_page") && p.get("prev_page").canConvertToInt()) prev = p.get("prev_page").asInt();
                        // total opcional; no necesario para el mini-resumen
                    }
                }
                // Completar page/size desde querySnapshot si faltan
                if ((page == null || size == null) && chosen.querySnapshot != null && chosen.querySnapshot.isObject()) {
                    if (page == null && chosen.querySnapshot.has("page") && chosen.querySnapshot.get("page").canConvertToInt()) page = chosen.querySnapshot.get("page").asInt();
                    if (size == null && chosen.querySnapshot.has("size") && chosen.querySnapshot.get("size").canConvertToInt()) size = chosen.querySnapshot.get("size").asInt();
                }
                // Derivar prev/next si faltan, basados en page/hasMore
                if (prev == null && page != null && page > 1) prev = page - 1;
                if (next == null && page != null && Boolean.TRUE.equals(hasMore)) next = page + 1;
            }
            if (tipo == null) tipo = parametros.getDefaultType();
            if (page == null) page = 1;
            if (size == null) size = 50;
            if (returned == null) returned = 0;
            if (hasMore == null) {
                hasMore = (returned >= size); // mejor-esfuerzo cuando faltan metadatos
            }
            StringBuilder sb = new StringBuilder();
            sb.append("paginacion: tipo=").append(tipo)
              .append("; page=").append(page)
              .append("; size=").append(size)
              .append("; returned=").append(returned)
              .append("; has_more=").append(hasMore)
              .append("; prev=").append(prev == null ? "null" : String.valueOf(prev))
              .append("; next=").append(next == null ? "null" : String.valueOf(next));
            int sampleVal = sampleOf == null ? 0 : Math.max(0, sampleOf);
            boolean plural;
            if (returned > 1) plural = true; else if (returned == 1 && sampleVal >= 1) plural = true; else plural = false;
            boolean prevAllowed = page != null && page > 1;
            boolean nextAllowed = Boolean.TRUE.equals(hasMore);
            sb.append("\neco: sample_of=").append(sampleVal)
              .append("; plural=").append(plural)
              .append("; nav: prev_allowed=").append(prevAllowed)
              .append("; next_allowed=").append(nextAllowed);
            return sb.toString();
        } catch (Exception __mini) {
            return "paginacion: tipo=items; page=1; size=50; returned=0; has_more=false; prev=null; next=null\neco: sample_of=0";
        }
    }

    // Añade contextToken a ui_suggestions de tipo Paginacion si falta y existe paginación real
    private void enrichPaginationSuggestionsWithTokens(ResponseEnvelope env, String type, PaginationInfo pagination) {
        if (env == null || env.getUiSuggestions() == null || pagination == null) return;
        Integer cur = pagination.getPage();
        Integer size = pagination.getSize();
        Integer next = pagination.getNextPage();
        Integer prev = pagination.getPrevPage();
        Boolean hasMore = pagination.getHasMore();
        if (cur == null || size == null) return;
        for (Suggestion s : env.getUiSuggestions()) {
            if (s == null) continue;
            if (!"Paginacion".equalsIgnoreCase(s.getType())) continue;
            if (s.getContextToken() != null && !s.getContextToken().isBlank()) continue; // ya tiene token
            String direction = null;
            Integer targetPage = null;
            // Intentar leer direction desde los metadatos de la sugerencia
            if (s.getPagination() != null && s.getPagination().getDirection() != null) {
                String dir = s.getPagination().getDirection().toLowerCase();
                if ("next".equals(dir)) {
                    direction = "next";
                    targetPage = (next != null) ? next : (hasMore != null && hasMore ? cur + 1 : null);
                } else if ("prev".equals(dir) || "previous".equals(dir)) {
                    direction = "prev";
                    targetPage = (prev != null) ? prev : (cur > 1 ? cur - 1 : null);
                }
            }
            // Heurística por displayText si no se detectó
            if (direction == null) {
                String text = s.getDisplayText() == null ? "" : s.getDisplayText().toLowerCase();
                if (text.contains("siguiente") || text.contains("ver más") || text.contains("ver mas") || text.contains("next")) {
                    direction = "next";
                    targetPage = (next != null) ? next : (hasMore != null && hasMore ? cur + 1 : null);
                } else if (text.contains("anterior") || text.contains("previo") || text.contains("previous")) {
                    direction = "prev";
                    targetPage = (prev != null) ? prev : (cur > 1 ? cur - 1 : null);
                }
            }
            if (direction != null && targetPage != null) {
                java.util.Map<String,Object> payload = new java.util.HashMap<>();
                payload.put("type", type != null ? type : (env.getData() != null ? env.getData().getType() : ""));
                payload.put("page", cur);
                payload.put("size", size);
                payload.put("target_page", targetPage);
                String token = contextTokenService.sign(payload);
                s.setContextToken(token);
                // Asegurar coherencia en los metadatos de la sugerencia
                if (s.getPagination() == null) {
                    s.setPagination(new Suggestion.PaginationSuggestion(direction, targetPage, size));
                } else {
                    s.getPagination().setDirection(direction);
                    s.getPagination().setPage(targetPage);
                    s.getPagination().setSize(size);
                }
            }
        }
    }

    

    // Se eliminaron las utilidades de renderizado Markdown/ficha para devolver siempre JSON/texto-encapsulado
}
