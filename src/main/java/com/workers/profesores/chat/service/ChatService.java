package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.config.ParametrosArbolDecision2CallOpenAI;
import com.workers.profesores.chat.config.ParametrosPresentacionSegundoTurno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.workers.profesores.chat.dto.response.*;
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
    private final ParametrosPresentacionSegundoTurno presentacion;
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${backend.debug:false}")
    private boolean debug;
    // Flags se obtienen desde ParametrosArbolDecision2CallOpenAI para evitar duplicidad de fuentes
    private boolean fastpathEnabled;
    private boolean secondTurnLiteEnabled;
    // Note: local welcome handling removed to always delegate to OpenAI

    // Exec metadata to support fast-path without a second OpenAI round-trip
    @SuppressWarnings("unused")
    private static class ExecMeta {
        String endpointName;
        String method;
        JsonNode apiResultNode;
        String raw;
    }

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
        this(openai, apiProxy, jwtDelegationService, parametros, new ParametrosPresentacionSegundoTurno());
    }

    // Convenience constructor for unit tests that don't wire Spring configuration properties
    public ChatService(OpenAICallApiService openai,
                       ApiProxyService apiProxy,
                       com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService) {
        this(openai, apiProxy, jwtDelegationService, new ParametrosArbolDecision2CallOpenAI(), new ParametrosPresentacionSegundoTurno());
    }

    

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy, com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService,
                       ParametrosArbolDecision2CallOpenAI parametros,
                       ParametrosPresentacionSegundoTurno presentacion) {
        this.openai = openai;
        this.apiProxy = apiProxy;
        this.jwtDelegationService = jwtDelegationService;
        this.parametros = parametros == null ? new ParametrosArbolDecision2CallOpenAI() : parametros;
        this.presentacion = presentacion == null ? new ParametrosPresentacionSegundoTurno() : presentacion;
    // Inicializa flags desde parámetros (ConfigurationProperties)
    this.fastpathEnabled = this.parametros.isFastpathEnabled();
    this.secondTurnLiteEnabled = this.parametros.isSecondTurnLiteEnabled();
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
            // 0) System prompt base + whitelist dinámica
            String promptBase = "Eres un asistente (secretaria) para una plataforma de academias en España. Solo puedes acceder a los recursos de la API mediante la función call_api y siempre bajo las condiciones de autorizacion que tenga el rol del usuario logueado. Cuando necesites datos, usa exclusivamente call_api con los endpoints permitidos. Responde en castellano, de forma breve y clara. Si necesitas confirmar una operación destructiva, pide confirmación explícita antes de ejecutar. Cuando pidas listados grandes, sugiere exportar a CSV/Excel en lugar de mostrar miles de filas. No uses tablas Markdown en el system prompt ni en las instrucciones del sistema.";
                String whitelistTable = openai.renderEndpointsTable();
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

            StringBuilder systemPromptSb = new StringBuilder(promptBase);
            if (claims != null) {
                String rolesStr = claims.roles == null ? "[]" : claims.roles.toString();
                String academiaStr = claims.academiaId == null ? "null" : claims.academiaId.toString();
                systemPromptSb.append(" Contexto del usuario: roles=").append(rolesStr).append(", academiaId=").append(academiaStr).append(". ");
                systemPromptSb.append("Regla de ámbito: Si el usuario tiene rol Admin_academia, asume siempre ámbito 'academia' y limita las operaciones a esa academia. ");
                systemPromptSb.append("Si el usuario tiene rol Profesor_academia, su ámbito está restringido a la academia y a los cursos con los que esté vinculado en dicha academia: el profesor solo puede consultar información de la academia y de sus propios cursos, y puede modificar aspectos de los cursos a los que está asignado (por ejemplo: crear sesiones, añadir anotaciones, consultar la lista de alumnos del curso, sus notas y su progreso). No realices operaciones sobre otras academias ni sobre cursos donde el profesor no esté vinculado; si la intención implica afectar a otro ámbito, pide confirmación o aclaración antes de ejecutar acciones con impacto.");
                systemPromptSb.append("Si el usuario tiene rol Admin_plataforma, el modelo debe intentar inferir por el contexto y las últimas peticiones si la operación se refiere a una academia concreta; en ese caso asume ámbito 'academia' (por ejemplo usando academia_id). Si no está claro, pide confirmación antes de ejecutar acciones con impacto (crear/borrar/editar). ");
            }
            // Añadimos el perfil del usuario (si se pudo obtener) para que el modelo pueda usar el nombre y otros datos sin necesidad de tool calls adicionales
            systemPromptSb.append(" Perfil_usuario: ").append(profileJsonForPrompt).append(".");
            // Instrucción explícita: usar call_api para cualquier acceso adicional a endpoints y devolver JSON estructurado
            systemPromptSb.append(" ").append(whitelistTable).append("\n\n");
            // Reglas concisas de salida: priorizamos que el modelo haga el formateo humano
            systemPromptSb.append(
                "Reglas de salida (estrictas y concisas):\n" +
                "- Devuelve SOLO un objeto JSON válido (sin texto fuera del JSON).\n" +
                "- 'text': resumen breve y natural en castellano (España).\n" +
                "- Listados: devuelve SIEMPRE un array bajo la clave plural exacta ('usuarios'|'academias'|'cursos'|'alumnos'|'profesores').\n" +
                "  - Copia tal cual las propiedades originales de la API.\n" +
                "  - Si calculas campos derivados (p.ej., conteos), AÑÁDELOS con nombres en castellano y amigables (ej.: 'numero_usuarios'), sin sobrescribir los originales ni usar '*_count' ni inglés.\n" +
                "  - 'summary_fields': incluye 2 claves RELEVANTES existentes en los ítems (p.ej., ['nombre','email'] o ['id','nombre']).\n" +
                "  - 'pagination': inclúyelo sólo si el endpoint es paginado, con los valores reales (no inventes).\n" +
                "- 'suggestions': 2–5 próximas acciones útiles (paginación, filtros, exportación, ver detalle, etc.).\n" +
                "- Saludos/charla: si es un saludo, NO uses call_api. Devuelve { 'text': ..., 'suggestions': [...] }.\n"
            );
            // Regla para consultas con agregaciones: encadenar tool_calls en el primer turno
            systemPromptSb.append(
                "\nAgregaciones (\"para cada\", \"por\", \"agrupar\", \"cuántos por...\"):\n" +
                "- Si la intención requiere calcular algo por elemento (p.ej., 'para cada academia cuántos usuarios tiene'), EMITE en tu PRIMER mensaje TODAS las tool_calls necesarias para completar la tarea, sin detenerte tras la primera.\n" +
                "  Ejemplo de plan: (1) listar academias; (2) para cada academia del resultado, llamar a 'usuarios.listar_usuarios' filtrando por 'academia_id=ID'.\n" +
                "- No devuelvas una respuesta parcial solo con el listado base cuando falten llamadas adicionales para responder.\n" +
                "- Si necesitas varias llamadas, usa 'call_api_batch' con un array 'calls'. Si la lista es larga, limita a N (p.ej., 3) y sugiere 'continuar' en 'suggestions'.\n"
            );
            // Navegación mínima
            systemPromptSb.append(
                "\nNavegación mínima:\n" +
                "- Usa 'Contexto_paginacion' del historial si existe.\n" +
                "- 'Siguiente' => mismo endpoint y filtros, query.page = next_page o page+1. 'Anterior' => prev_page o page-1 (>=1). 'Ir a página N' => page=N.\n"
            );
            // Mini-ejemplo de salida para reforzar nombres en castellano y summary_fields
            systemPromptSb.append(
                "\nEjemplo breve de salida (guía):\n" +
                "Entrada: 'para cada academia, cuántos usuarios tiene'\n" +
                "Salida (solo estructura): {\n" +
                "  'text': 'He obtenido el número de usuarios por academia.',\n" +
                "  'academias': [ { 'id': 1, 'nombre': 'Academia A', 'numero_usuarios': 12 } ],\n" +
                "  'summary_fields': ['nombre','id'],\n" +
                "  'suggestions': ['Ver detalles de una academia','Listar usuarios de una academia']\n" +
                "}\n"
            );
            // Micro-ejemplo de tool_call batch (guía para el primer turno)
            systemPromptSb.append(
                "\nEjemplo call_api_batch (solo estructura):\n" +
                "assistant.tool_call => name: 'call_api_batch', arguments: {\n" +
                "  'calls': [\n" +
                "    { 'name': 'academias.listar_academias', 'method': 'GET' },\n" +
                "    { 'name': 'usuarios.listar_usuarios', 'method': 'GET', 'query': { 'academia_id': 308 } },\n" +
                "    { 'name': 'usuarios.listar_usuarios', 'method': 'GET', 'query': { 'academia_id': 309 } }\n" +
                "  ]\n" +
                "}\n" +
                "(Si hay muchas academias, limita a 3 y sugiere 'continuar' en 'suggestions').\n"
            );
            String systemPrompt = systemPromptSb.toString();
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
            for (ChatRequest.Message m : incoming) {
                String role = m.getRole();
                if ("system".equals(role)) continue;
                seed.add(Map.of(
                    "role", m.getRole(),
                    "content", m.getContent()
                ));
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Mensajes de usuario preparados para OpenAI");

            // Removed local welcome shortcut: all messages now go through OpenAI

            if (debug) {
                System.out.println("[ChatService][DEBUG] Seed messages for OpenAI: " + seed);
            }

            // 2) Primer turno al modelo (puede devolver tool_calls)

            if (debug) {
                logger.debug("[ChatService] Calling OpenAI with seed messages (seedSize={})", seed.size());
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (seedSize=" + seed.size() + ")");
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (callChatWithTools)");
            // Pasamos el Authorization (Bearer token) a la llamada a OpenAI service para que las herramientas puedan acceder al token si es necesario
            String rawFirst = openai.callChatWithTools(seed, xmlLogger, authorization);
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
                    return applyFinalFallback(buildEnvelopeFromContentNode(contentNode));
                } catch (Exception e) {
                    // Intentamos pedir al modelo que convierta la respuesta anterior en JSON válido siguiendo el contrato
                    if (debug) System.out.println("[ChatService][DEBUG] Content not JSON, requesting reformat to JSON from OpenAI");
                    try {
                        List<Map<String, Object>> reformatSeed = new ArrayList<>();
                        reformatSeed.add(systemMsg);
                        if (profileJsonForPrompt != null && profileJsonForPrompt.trim().length() > 2 && !profileJsonForPrompt.trim().equals("{}")) {
                            reformatSeed.add(Map.of("role", "system", "content", "Perfil_usuario: " + profileJsonForPrompt));
                        }
                        reformatSeed.add(Map.of("role", "assistant", "content", content));
                        // Instrucción clara y estricta para devolver JSON
                        String reformatInstruction = "Por favor, devuelve únicamente un objeto JSON válido con al menos la propiedad 'text' (string). Opcionalmente puedes incluir 'suggestions' (array de strings), 'academia' (objeto) o 'academias' (array), cualquier lista bajo las claves exactas 'usuarios'|'academias'|'cursos'|'alumnos'|'profesores', y 'summary_fields' (array de 1–2 strings con nombres de campos existentes en los ítems). No incluyas explicaciones ni texto fuera del JSON. " +
                            "Si ya había sugerencias inclúyelas en 'suggestions'. Si mencionas el nombre del usuario, ponlo dentro de 'text' o como parte del texto.";
                        reformatSeed.add(Map.of("role", "user", "content", reformatInstruction));
                        String reformatted = openai.callChatWithTools(reformatSeed, xmlLogger, authorization);
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
                                JsonNode rc = rep.path("choices").get(0).path("message").path("content");
                                String rcStr = rc.isMissingNode() ? "" : rc.asText("");
                                try { JsonNode rcNode = om.readTree(rcStr); return buildEnvelopeFromContentNode(rcNode); } catch (Exception exx) { /* fall through */ }
                            }
                        } catch (Exception ignore) {
                        }
                        // Si la respuesta no contenía 'choices' o no devolvió JSON directo, intentamos parsearla como JSON directa
                        try {
                            JsonNode reformNode = om.readTree(reformatted);
                            return buildEnvelopeFromContentNode(reformNode);
                        } catch (Exception ex2) {
                            // Fallback: devolver el texto original envuelto en text
                            if (debug) System.out.println("[ChatService][DEBUG] Reformatting failed, returning fallback text JSON.");
                            return applyFinalFallback(ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of()));
                        }
                    } catch (Exception ex) {
                        if (debug) System.out.println("[ChatService][DEBUG] Exception while reformatting content: " + ex.getMessage());
                        return applyFinalFallback(ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of()));
                    }
                }
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
                return ResponseEnvelope.error("Delegación deshabilitada","delegation_disabled","Delegation disabled or missing delegation secret - cannot proxy API calls", List.of());
            }

            // 3) Resolver tool_calls

            List<Map<String, Object>> toolOutputs = new ArrayList<>();
            // Keep metadata of executed tools to enable fast-path without a second OpenAI round-trip
            List<ExecMeta> executed = new ArrayList<>();
            
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
                                if (epB == null) {
                                    apiResB = "{\"error\":\"Endpoint no permitido: " + endpointNameB + "\"}";
                                } else {
                                    try {
                                        String authToUseB = (delegatedAuth != null) ? delegatedAuth : authorization;
                                        if (xmlLogger != null) {
                                            apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, queryB, bodyB, authToUseB, xmlLogger);
                                        } else {
                                            apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, queryB, bodyB, authToUseB);
                                        }
                                    } catch (Exception exInnerB) {
                                        Map<String, Object> errMapB = Map.of("error", "authorization_failure", "message", exInnerB.getMessage() == null ? "" : exInnerB.getMessage());
                                        apiResB = om.writeValueAsString(errMapB);
                                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "Fallo autorización (batch): " + exInnerB.getMessage());
                                    }
                                }
                                // track executed meta por call individual
                                try {
                                    JsonNode parsedB = om.readTree(apiResB);
                                    ExecMeta metaB = new ExecMeta();
                                    metaB.endpointName = endpointNameB;
                                    metaB.method = methodB;
                                    metaB.apiResultNode = parsedB;
                                    metaB.raw = apiResB;
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
                    String contentBatch = om.writeValueAsString(Map.of("ok", true, "results", batchResults));
                    toolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", contentBatch
                    ));
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
                // Fallback mínimo: si el modelo omite query.page en navegación y tenemos Contexto_paginacion, completar page/size
                try {
                    // Construir objeto mutable para query
                    com.fasterxml.jackson.databind.node.ObjectNode q = (query != null && query.isObject()) ? (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy() : om.createObjectNode();
                    boolean hasPage = q.has("page") && q.get("page").canConvertToInt();
                    // Extraer último comando del usuario y contexto de paginación del historial de entrada
                    String lastUser = getLastUserUtterance(incoming);
                    com.fasterxml.jackson.databind.node.ObjectNode ctx = extractPaginationContext(incoming);
                    if (!hasPage && ctx != null && lastUser != null) {
                        int curPage = ctx.has("page") && ctx.get("page").canConvertToInt() ? Math.max(1, ctx.get("page").asInt()) : 1;
                        Integer next = ctx.has("next_page") && ctx.get("next_page").canConvertToInt() ? ctx.get("next_page").asInt() : null;
                        Integer prev = ctx.has("prev_page") && ctx.get("prev_page").canConvertToInt() ? ctx.get("prev_page").asInt() : null;
                        Integer size = ctx.has("size") && ctx.get("size").canConvertToInt() ? ctx.get("size").asInt() : null;
                        Integer gotoN = extractGotoPage(lastUser);
                        String lu = lastUser.toLowerCase(java.util.Locale.ROOT);
                        if (gotoN != null && gotoN >= 1) {
                            q.put("page", gotoN);
                        } else if (lu.contains("siguiente")) {
                            if (next != null) q.put("page", next);
                            else q.put("page", curPage + 1);
                        } else if (lu.contains("anterior")) {
                            if (prev != null) q.put("page", Math.max(1, prev));
                            else q.put("page", Math.max(1, curPage - 1));
                        }
                        if (size != null && !q.has("size")) q.put("size", size);
                        query = q; // sustituimos por la versión enriquecida
                    }
                } catch (Exception ignore) { /* fallback best-effort */ }
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
                    // Antes de ejecutar, validar permisos y sanitizar parámetros
                    try {
                        // Prefer delegated token if available, otherwise forward original authorization
                        String authToUse = (delegatedAuth != null) ? delegatedAuth : authorization;
                        if (xmlLogger != null) {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse, xmlLogger);
                        } else {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse);
                        }
                    } catch (Exception exInner) {
                        // Ensure apiResult is always a JSON string describing the error
                        Map<String, Object> errMap = Map.of("error", "authorization_failure", "message", exInner.getMessage() == null ? "" : exInner.getMessage());
                        apiResult = om.writeValueAsString(errMap);
                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "Fallo autorización: " + exInner.getMessage());
                    }
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
                executed.add(meta);
            }


            // 4) Segundo turno ligero (preferido si procede): exactamente 1 tool_call, GET listado conocido, sin error y con returned/paginación
            if (secondTurnLiteEnabled) {
                try {
                    ResponseEnvelope liteEnv = trySecondTurnLite(executed, seed, assistantMsg, xmlLogger, authorization);
                    if (liteEnv != null) {
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Segundo turno ligero aplicado");
                        if (debug) logger.debug("[ChatService] Second-turn LITE applied");
                        return applyFinalFallback(liteEnv);
                    }
                } catch (Exception liteEx) {
                    if (debug) logger.debug("[ChatService] Second-turn LITE failed: {}", liteEx.getMessage());
                }
            }

            // 5) Fast-path pre-2º turno configurable (por defecto desactivado para no decidir intenciones en backend)
            if (parametros.isPreSecondFastpathEnabled() && fastpathEnabled && !executed.isEmpty()) {
                try {
                    ResponseEnvelope fastEnv = buildFastEnvelopeFromApi(executed);
                    if (fastEnv != null) {
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Fast-path PRE-2º turno aplicado (flag ON)");
                        if (debug) logger.debug("[ChatService] Pre-second fast-path applied (flag)");
                        return applyFinalFallback(fastEnv);
                    }
                } catch (Exception fastEx) {
                    if (debug) logger.debug("[ChatService] Pre-second fast-path failed: {}", fastEx.getMessage());
                }
            }

            // 6) Segundo turno normal: antes de llamar, aplica time-budget fallback a fast-path si ya excedimos el umbral
            try {
                long elapsedBeforeSecond = System.currentTimeMillis() - t0;
                long budget = parametros.getSecondTurnBudgetMs();
                // Si ya llevamos >budget y podemos construir fast-path, evitar la segunda llamada al LLM
                if (fastpathEnabled && elapsedBeforeSecond > budget) {
                    ResponseEnvelope fastBudget = buildFastEnvelopeFromApi(executed);
                    if (fastBudget != null) {
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Time-budget alcanzado antes del 2º turno: aplicando fast-path");
                        if (debug) logger.debug("[ChatService] Time-budget exceeded ({} ms > {}). Returning fast-path envelope.", elapsedBeforeSecond, budget);
                        return applyFinalFallback(fastBudget);
                    }
                }
            } catch (Exception ignore) { /* best-effort budget check */ }

            // 6) Segundo turno normal: reinyectamos el assistant con sus tool_calls + los outputs
            List<Map<String, Object>> followup = new ArrayList<>(seed);
            Map<String, Object> assistantEcho = new HashMap<>();
            assistantEcho.put("role", "assistant");
            assistantEcho.put("content",
                assistantMsg.path("content").isMissingNode() ? "" : assistantMsg.path("content").asText("")
            );
            assistantEcho.put("tool_calls", om.convertValue(assistantMsg.path("tool_calls"), List.class));
            followup.add(assistantEcho);
            // Eco recortado: limitar arrays en toolOutputs a una muestra pequeña + metadatos
            int sampleN = Math.max(0, parametros.getModelEchoSampleSize());
            for (Map<String,Object> to : toolOutputs) {
                try {
                    Object contentObj = to.get("content");
                    String contentStr = contentObj == null ? null : String.valueOf(contentObj);
                    JsonNode cnode = contentStr == null ? null : om.readTree(contentStr);
                    if (sampleN > 0 && cnode != null && cnode.isObject()) {
                        JsonNode items = null;
                        // buscar clave de array conocida o 'items'
                        for (String k : parametros.getAllowedTargetsPlural()) {
                            if (cnode.has(k) && cnode.get(k).isArray()) { items = cnode.get(k); break; }
                        }
                        if (items == null && cnode.has("items") && cnode.get("items").isArray()) items = cnode.get("items");
                        if (items != null && items.size() > sampleN) {
                            com.fasterxml.jackson.databind.node.ObjectNode trimmed = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                            for (int i=0;i<sampleN;i++) arr.add(items.get(i));
                            // reemplazar array original por la muestra
                            boolean replaced = false;
                            for (String k : parametros.getAllowedTargetsPlural()) {
                                if (trimmed.has(k) && trimmed.get(k).isArray()) { trimmed.set(k, arr); replaced = true; break; }
                            }
                            if (!replaced && trimmed.has("items") && trimmed.get("items").isArray()) trimmed.set("items", arr);
                            // añadir metadatos returned/sample_of para que el modelo comprenda que hay más
                            trimmed.put("returned", items.size());
                            trimmed.put("sample_of", sampleN);
                            to = new HashMap<>(to);
                            to.put("content", om.writeValueAsString(trimmed));
                        }
                    }
                } catch (Exception ignore) {}
                followup.add(to);
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda llamada a OpenAI (reinyectando resultados de tools)");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Calling OpenAI with followup messages: " + followup);
                }
            JsonNode second = om.readTree(openai.callChatWithTools(followup, xmlLogger, authorization));
            // Telemetría: fin de la primera llamada del segundo turno
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "decision_ms_first_second_turn=" + (System.currentTimeMillis() - t0));
            if (debug) logger.debug("[Telemetry] decision_ms_first_second_turn={} (since start)", (System.currentTimeMillis() - t0));
            if (debug) {
                try {
                    String prettySecond = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(second);
                    System.out.println("[ChatService][DEBUG] OpenAI second response (pretty):\n" + prettySecond);
                } catch (Exception ignore) {
                    System.out.println("[ChatService][DEBUG] OpenAI second response: " + second);
                }
            }
            JsonNode finalMsg = second.path("choices").get(0).path("message");
            // Bucle limitado: si el 2º turno devuelve tool_calls, ejecútalos y vuelve a llamar (máx 2 iteraciones o 8s presupuesto)
            int extraIters = 0;
            final long iterationBudgetMs = parametros.getSecondTurnBudgetMs();
            final int maxIters = Math.max(0, parametros.getSecondTurnMaxExtraIterations());
            while (finalMsg != null && finalMsg.has("tool_calls") && extraIters < maxIters && (System.currentTimeMillis() - t0) < iterationBudgetMs) {
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Segundo turno - iteración extra " + (extraIters+1) + ": procesando tool_calls");
                // Ejecutar tool_calls devueltos por el 2º turno
                List<Map<String, Object>> iterToolOutputs = new ArrayList<>();
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
                                    if (epB == null) {
                                        apiResB = "{\"error\":\"Endpoint no permitido: " + endpointNameB + "\"}";
                                    } else {
                                        try {
                                            String authToUseB = (delegatedAuth != null) ? delegatedAuth : authorization;
                                            if (xmlLogger != null) {
                                                apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, queryB, bodyB, authToUseB, xmlLogger);
                                            } else {
                                                apiResB = apiProxy.executeSpecCall(epB, methodB, pathParamsB, queryB, bodyB, authToUseB);
                                            }
                                        } catch (Exception exInnerB) {
                                            Map<String, Object> errMapB = Map.of("error", "authorization_failure", "message", exInnerB.getMessage() == null ? "" : exInnerB.getMessage());
                                            apiResB = om.writeValueAsString(errMapB);
                                            if (xmlLogger != null) xmlLogger.addStep("Authorization", "[iter] Fallo autorización (batch): " + exInnerB.getMessage());
                                        }
                                    }
                                    try {
                                        JsonNode parsedB = om.readTree(apiResB);
                                        ExecMeta metaB = new ExecMeta();
                                        metaB.endpointName = endpointNameB;
                                        metaB.method = methodB;
                                        metaB.apiResultNode = parsedB;
                                        metaB.raw = apiResB;
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
                        String contentBatch = om.writeValueAsString(Map.of("ok", true, "results", batchResults));
                        iterToolOutputs.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", contentBatch
                        ));
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
                        String authToUse = (delegatedAuth != null) ? delegatedAuth : authorization;
                        if (xmlLogger != null) {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse, xmlLogger);
                        } else {
                            apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authToUse);
                        }
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
                    executed.add(meta);
                }
                // Construir nuevo followup para la siguiente iteración (aplicando también eco recortado)
                Map<String, Object> iterAssistantEcho = new HashMap<>();
                iterAssistantEcho.put("role", "assistant");
                iterAssistantEcho.put("content", finalMsg.path("content").isMissingNode() ? "" : finalMsg.path("content").asText(""));
                iterAssistantEcho.put("tool_calls", om.convertValue(finalMsg.path("tool_calls"), List.class));
                followup.add(iterAssistantEcho);
                int sampleN2 = Math.max(0, parametros.getModelEchoSampleSize());
                for (Map<String,Object> to2 : iterToolOutputs) {
                    try {
                        Object contentObj = to2.get("content");
                        String contentStr = contentObj == null ? null : String.valueOf(contentObj);
                        JsonNode cnode = contentStr == null ? null : om.readTree(contentStr);
                        if (sampleN2 > 0 && cnode != null && cnode.isObject()) {
                            JsonNode items = null;
                            for (String k : parametros.getAllowedTargetsPlural()) {
                                if (cnode.has(k) && cnode.get(k).isArray()) { items = cnode.get(k); break; }
                            }
                            if (items == null && cnode.has("items") && cnode.get("items").isArray()) items = cnode.get("items");
                            if (items != null && items.size() > sampleN2) {
                                com.fasterxml.jackson.databind.node.ObjectNode trimmed = (com.fasterxml.jackson.databind.node.ObjectNode) cnode.deepCopy();
                                com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                                for (int i=0;i<sampleN2;i++) arr.add(items.get(i));
                                boolean replaced = false;
                                for (String k : parametros.getAllowedTargetsPlural()) {
                                    if (trimmed.has(k) && trimmed.get(k).isArray()) { trimmed.set(k, arr); replaced = true; break; }
                                }
                                if (!replaced && trimmed.has("items") && trimmed.get("items").isArray()) trimmed.set("items", arr);
                                trimmed.put("returned", items.size());
                                trimmed.put("sample_of", sampleN2);
                                to2 = new HashMap<>(to2);
                                to2.put("content", om.writeValueAsString(trimmed));
                            }
                        }
                    } catch (Exception ignore) {}
                    followup.add(to2);
                }
                // Nueva llamada a OpenAI con los nuevos tool outputs
                second = om.readTree(openai.callChatWithTools(followup, xmlLogger, authorization));
                if (debug) {
                    try {
                        String prettySecondIter = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(second);
                        System.out.println("[ChatService][DEBUG] OpenAI second response (iter " + (extraIters+1) + "):\n" + prettySecondIter);
                    } catch (Exception ignore) {}
                }
                finalMsg = second.path("choices").get(0).path("message");
                extraIters++;
            }
            if (xmlLogger != null) xmlLogger.addStep("Telemetry", "decision_ms=" + (System.currentTimeMillis() - t0));
            if (debug) logger.debug("[Telemetry] decision_ms={} (since start)", (System.currentTimeMillis() - t0));
            String finalContent = (finalMsg == null || finalMsg.isMissingNode()) ? "" : finalMsg.path("content").asText("");
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
                JsonNode enriched = maybeEnrichWithExecutedItems(node, executed);
                ResponseEnvelope env = buildEnvelopeFromContentNode(enriched);
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
                ResponseEnvelope env = ResponseEnvelope.success(finalContent, DataSection.of("chat", List.of(), null), List.of(), List.of());
                if (xmlLogger != null) xmlLogger.addStep("Telemetry", "render_ms=" + (System.currentTimeMillis() - t0));
                if (debug) logger.debug("[Telemetry] render_ms={} (since start)", (System.currentTimeMillis() - t0));
                if (debug) {
                    try {
                        String prettyEnv = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(env);
                        System.out.println("[ChatService][DEBUG] Envelope(final_plain_text):\n" + prettyEnv);
                    } catch (Exception ignore) {}
                }
                return applyFinalFallback(env);
            }

        } catch (Exception e) {
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Excepción en runChat: " + e.getMessage());
            if (debug) {
                System.out.println("[ChatService][DEBUG] Exception in runChat: " + e.getMessage());
                e.printStackTrace();
            }
            return ResponseEnvelope.error("Error en runChat","internal_error", e.getMessage(), List.of());
        }
    }

    // Extrae el último mensaje del usuario (role="user") del historial; devuelve null si no existe
    private String getLastUserUtterance(List<ChatRequest.Message> incoming) {
        if (incoming == null || incoming.isEmpty()) return null;
        for (int i = incoming.size() - 1; i >= 0; i--) {
            ChatRequest.Message m = incoming.get(i);
            try {
                if (m != null && "user".equalsIgnoreCase(m.getRole())) {
                    String c = m.getContent();
                    if (c != null && !c.isBlank()) return c;
                }
            } catch (Exception ignore) { /* best-effort */ }
        }
        return null;
    }

    // Busca en el historial un assistant con prefijo "Contexto_paginacion: { ... }" y devuelve el JSON como ObjectNode
    private com.fasterxml.jackson.databind.node.ObjectNode extractPaginationContext(List<ChatRequest.Message> incoming) {
        if (incoming == null || incoming.isEmpty()) return null;
        String marker = "Contexto_paginacion:";
        // buscamos el último que aparezca para que tenga prioridad el más reciente
        for (int i = incoming.size() - 1; i >= 0; i--) {
            ChatRequest.Message m = incoming.get(i);
            try {
                if (m == null) continue;
                String role = m.getRole();
                if (!"assistant".equalsIgnoreCase(role)) continue;
                String content = m.getContent();
                if (content == null) continue;
                int idx = content.indexOf(marker);
                if (idx < 0) continue;
                String after = content.substring(idx + marker.length()).trim();
                // si viene entrecomillado, intenta quitar comillas exteriores
                if (after.startsWith("\"") && after.endsWith("\"")) {
                    after = after.substring(1, after.length() - 1);
                }
                // intenta localizar el bloque JSON desde la primera '{' hasta la última '}'
                int open = after.indexOf('{');
                int close = after.lastIndexOf('}');
                String json = (open >= 0 && close > open) ? after.substring(open, close + 1) : after;
                JsonNode parsed = om.readTree(json);
                if (parsed != null && parsed.isObject()) {
                    return (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
                }
            } catch (Exception ignore) { /* si falla el parse, sigue buscando */ }
        }
        return null;
    }

    // Intenta extraer un número de página de instrucciones tipo "Ir a página N" (admite variantes con/ sin acento y "pag")
    private Integer extractGotoPage(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            // Patrones comunes en castellano
            java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("(?i)\\b(?:ir\\s+a(?:\\s+la)?)?\\s*(?:p[áa]g(?:ina)?|pag\\.?|pagina|página)\\s*(\\d+)\\b");
            java.util.regex.Matcher m1 = p1.matcher(t);
            if (m1.find()) {
                int n = Integer.parseInt(m1.group(1));
                return n >= 1 ? n : 1;
            }
            // También admitir mensajes del tipo "página 3" sin el "ir a"
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("(?i)\\b(?:p[áa]gina|pagina|pag)\\s*(\\d+)\\b");
            java.util.regex.Matcher m2 = p2.matcher(t);
            if (m2.find()) {
                int n = Integer.parseInt(m2.group(1));
                return n >= 1 ? n : 1;
            }
            // Como último recurso, si el mensaje es solo un número
            java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*$");
            java.util.regex.Matcher m3 = p3.matcher(t);
            if (m3.find()) {
                int n = Integer.parseInt(m3.group(1));
                return n >= 1 ? n : 1;
            }
        } catch (Exception ignore) { }
        return null;
    }

    // (Eliminado) Lógica de detección de intención de agregación: el backend es un orquestador puro.

    // Intenta aplicar el segundo turno ligero; devuelve null si no procede
    private ResponseEnvelope trySecondTurnLite(List<ExecMeta> executed, List<Map<String,Object>> seed, JsonNode assistantMsg,
                                               com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization) throws Exception {
        if (executed == null) return null;
        if (parametros.isRequireSingleToolCall() && executed.size() != 1) return null;
        ExecMeta meta = executed.isEmpty() ? null : executed.get(0);
        if (meta == null || meta.apiResultNode == null) return null;
        // Método GET y sin error
        if (meta.method == null || parametros.getAllowedMethodsForLite().stream().noneMatch(m -> m.equalsIgnoreCase(meta.method))) return null;
        if (isErrorResponse(meta.apiResultNode)) return null;
        // Detectar target e items
        String target = extractTargetFromEndpointName(meta.endpointName);
        ItemsAndPagination ip = findItemsArray(meta.apiResultNode, target);
        if (ip == null || ip.items == null) return null;
        // Debe ser listado (0+ items) y elementos objeto o vacío
        if (ip.items.size() > 0 && !ip.items.get(0).isObject()) return null;
        // Fallback: si no pudimos extraer target del nombre del endpoint, intenta inferirlo por los campos de los ítems
        if (target == null) {
            String guessed = guessTargetFromItems(ip.items);
            if (guessed != null) target = guessed;
        }
        // Podemos determinar returned
        int returned = ip.items.size();
        // Construir descriptor compacto
        com.fasterxml.jackson.databind.node.ObjectNode descriptor = buildCompactDescriptor(target, returned, ip, meta.apiResultNode);
        // Mensajes followup: seed + assistant echo + user con instrucción + descriptor
        List<Map<String,Object>> followup = new ArrayList<>(seed);
        Map<String, Object> assistantEcho = new HashMap<>();
        assistantEcho.put("role", "assistant");
        assistantEcho.put("content", assistantMsg.path("content").isMissingNode() ? "" : assistantMsg.path("content").asText(""));
        assistantEcho.put("tool_calls", om.convertValue(assistantMsg.path("tool_calls"), List.class));
        followup.add(assistantEcho);
        String instruction = "Usa únicamente el descriptor anterior para redactar: 'text' (breve), 'suggestions' (2–5), 'summary_fields' (1–2). No repitas ni inventes la lista ni devuelvas arrays. Devuelve solo un objeto JSON válido con esas claves. Descriptor:";
        followup.add(Map.of("role","user","content", instruction + "\n" + descriptor.toString()));
    // response_format json_schema (parametrizado)
    Map<String,Object> schema = Map.of(
            "type","object",
            "additionalProperties", false,
            "properties", Map.of(
        "text", Map.of("type","string","maxLength", presentacion.getTextMaxLength()),
        "suggestions", Map.of(
            "type","array",
            "items", Map.of("type","string","maxLength", presentacion.getSuggestionItemMaxLen()),
            "minItems", presentacion.getSuggestionsMin(),
            "maxItems", presentacion.getSuggestionsMax()
        ),
        "summary_fields", Map.of(
            "type","array",
            "items", Map.of("type","string"),
            "minItems", presentacion.getSummaryFieldsMin(),
            "maxItems", presentacion.getSummaryFieldsMax()
        )
            ),
            "required", List.of("text")
        );
        Map<String,Object> extras = Map.of(
            "response_format", Map.of(
                "type","json_schema",
                "json_schema", Map.of("name","lite_second_turn","schema", schema)
            )
        );
        String raw = openai.callChatWithToolsWithExtras(followup, extras, xmlLogger, authorization);
        JsonNode second = om.readTree(raw == null ? "" : raw);
        JsonNode finalMsg = second.path("choices").get(0).path("message");
        String finalContent = finalMsg.path("content").asText("");
        JsonNode liteNode;
        try { liteNode = om.readTree(finalContent); } catch (Exception ex) { return null; }
        // Construir directamente el envelope: usamos items/paginación reales y el copy del 2º turno
        // Mensaje
        String msg = liteNode.has("text") && liteNode.get("text").isTextual() ? liteNode.get("text").asText("") : "";
        // Sugerencias
        List<String> sugg = new ArrayList<>();
        try {
            JsonNode sNode = liteNode.get("suggestions");
            if (sNode != null && sNode.isArray())
                for (JsonNode s : sNode) if (s.isTextual()) sugg.add(s.asText());
        } catch (Exception ignore) {}
        // Paginación
        PaginationInfo pagination = null;
        if (ip.pagination != null && ip.pagination.isObject()) {
            JsonNode p = ip.pagination;
            pagination = PaginationInfo.of(
                p.path("page").isNumber()? p.get("page").asInt() : null,
                p.path("size").isNumber()? p.get("size").asInt() : null,
                p.path("returned").isNumber()? p.get("returned").asInt() : ip.items.size(),
                p.path("has_more").isBoolean()? p.get("has_more").asBoolean() : null,
                p.path("next_page").isNumber()? p.get("next_page").asInt() : null,
                p.path("prev_page").isNumber()? p.get("prev_page").asInt() : null,
                p.path("total").isNumber()? p.get("total").asInt() : null
            );
        }
        if (target == null) target = guessTargetFromItems(ip.items);
        String type = (target != null) ? target : "chat";
        DataSection data = DataSection.of(type, ip.items, pagination);
        // summary_fields
        try {
            JsonNode sf = liteNode.get("summary_fields");
            if (sf != null && sf.isArray() && !ip.items.isEmpty()) {
                List<String> sfl = new ArrayList<>();
                for (JsonNode sfi : sf) if (sfi.isTextual()) sfl.add(sfi.asText());
                if (!sfl.isEmpty()) data.setSummaryFields(sfl);
            }
        } catch (Exception ignore) {}
        return ResponseEnvelope.success(msg, data, sugg, List.of());
    }

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
    private boolean isErrorResponse(JsonNode node) {
        if (node == null || node.isNull()) return true;
        if (node.has("error")) return true;
        if (node.has("ok") && !node.path("ok").asBoolean(true)) return true;
        return false;
    }

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

    // Busca el array de items con prioridad: node[target] -> node[items] -> (target==academias && node[result])
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

    // Construye el descriptor compacto siguiendo el documento
    private com.fasterxml.jackson.databind.node.ObjectNode buildCompactDescriptor(String target, int returned, ItemsAndPagination ip, JsonNode fullNode) {
        com.fasterxml.jackson.databind.node.ObjectNode d = om.createObjectNode();
        if (target != null) d.put("target", target);
        d.put("count", returned);
        // fields_present
        Set<String> keys = new LinkedHashSet<>();
    int scan = Math.min(ip.items.size(), presentacion.getFieldsScanLimit());
        for (int i=0;i<scan;i++) {
            JsonNode it = ip.items.get(i);
            if (it != null && it.isObject()) it.fieldNames().forEachRemaining(keys::add);
        }
        com.fasterxml.jackson.databind.node.ArrayNode fp = om.createArrayNode();
        for (String k : keys) fp.add(k);
        d.set("fields_present", fp);
        // sample_items (2–3, primitivas y truncado)
        com.fasterxml.jackson.databind.node.ArrayNode samples = om.createArrayNode();
    int sampleCount = Math.min(presentacion.getSampleItemsMax(), ip.items.size());
        for (int i=0;i<sampleCount;i++) {
            JsonNode it = ip.items.get(i);
            if (it != null && it.isObject()) samples.add(truncateAndRedact(it));
        }
        d.set("sample_items", samples);
        if (ip.pagination != null) d.set("pagination", ip.pagination);
        return d;
    }

    // Trunca strings a 50, ofusca emails y evita anidar objetos/arrays
    private com.fasterxml.jackson.databind.node.ObjectNode truncateAndRedact(JsonNode obj) {
        com.fasterxml.jackson.databind.node.ObjectNode out = om.createObjectNode();
        obj.fieldNames().forEachRemaining(fn -> {
            JsonNode v = obj.get(fn);
            if (v.isTextual()) {
                String s = v.asText("");
                String t = s.length()>presentacion.getTruncateStringLength() ? s.substring(0,presentacion.getTruncateStringLength())+"…" : s;
                if (presentacion.isObfuscateEmails() && t.contains("@")) {
                    int at = t.indexOf('@');
                    if (at>0) t = t.substring(0, Math.min(at, t.length())) + "@…";
                }
                out.put(fn, t);
            } else if (v.isNumber()) {
                out.put(fn, v.asText());
            } else if (v.isBoolean()) {
                out.put(fn, v.asBoolean());
            } else {
                // Ignorar objetos/arrays anidados para mantenerlo compacto
            }
        });
        return out;
    }

    // Fusiona los resultados del 2º turno (lite) con items/pagination reales
    @SuppressWarnings("unused")
    private com.fasterxml.jackson.databind.node.ObjectNode mergeLiteWithItems(String target, ItemsAndPagination ip, JsonNode lite) {
        com.fasterxml.jackson.databind.node.ObjectNode out = om.createObjectNode();
        // Copiar arrays de recurso reales
        if (target == null) {
            // Intento de última oportunidad: inferir target a partir de los ítems
            try {
                String guessed = guessTargetFromItems(ip.items);
                if (guessed != null) target = guessed;
            } catch (Exception ignore) { }
        }
        if (target != null) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
            for (JsonNode it : ip.items) arr.add(it);
            out.set(target, arr);
        } else {
            // fallback a clave genérica si no hay target (no debería aplicarse en LITE)
            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
            for (JsonNode it : ip.items) arr.add(it);
            out.set("items", arr);
        }
        if (ip.pagination != null) out.set("pagination", ip.pagination);
        if (lite != null && lite.isObject()) {
            if (lite.has("text")) out.set("text", lite.get("text"));
            if (lite.has("suggestions")) out.set("suggestions", lite.get("suggestions"));
            if (lite.has("summary_fields")) out.set("summary_fields", lite.get("summary_fields"));
        }
        return out;
    }

    // Construye un envelope sin segunda llamada al LLM a partir de los resultados de tools.
    // Regresa null si no puede determinar el tipo o el formato.
    private ResponseEnvelope buildFastEnvelopeFromApi(List<ExecMeta> executed) {
        try {
            if (executed == null || executed.isEmpty()) return null;
            // Para simplicidad, solo soportamos 1 tool_call en fast-path; si hay más, delegamos al LLM.
            if (executed.size() != 1) return null;
            ExecMeta meta = executed.get(0);
            if (meta == null || meta.apiResultNode == null) return null;

            String endpoint = meta.endpointName == null ? "" : meta.endpointName;
            JsonNode node = meta.apiResultNode;

            // Detectar listado de usuarios: API devuelve { items: [...], page?, size?, next_page?, prev_page?, has_more? }
            if (endpoint.toLowerCase(Locale.ROOT).contains("usuarios") && node.has("items") && node.get("items").isArray()) {
                com.fasterxml.jackson.databind.node.ObjectNode out = om.createObjectNode();
                // text corto
                int n = node.get("items").size();
                out.put("text", n > 0 ? "He obtenido " + n + " usuarios." : "No hay usuarios con ese criterio.");
                out.set("usuarios", node.get("items"));
                // pagination
                com.fasterxml.jackson.databind.node.ObjectNode pag = om.createObjectNode();
                pag.set("page", node.get("page") != null && node.get("page").isNumber()? node.get("page") : null);
                pag.set("size", node.get("size") != null && node.get("size").isNumber()? node.get("size") : null);
                pag.put("returned", n);
                if (node.has("has_more") && node.get("has_more").isBoolean()) pag.set("has_more", node.get("has_more"));
                if (node.has("next_page") && node.get("next_page").canConvertToInt()) pag.set("next_page", node.get("next_page"));
                if (node.has("prev_page") && node.get("prev_page").canConvertToInt()) pag.set("prev_page", node.get("prev_page"));
                if (node.has("total") && node.get("total").canConvertToInt()) pag.set("total", node.get("total"));
                out.set("pagination", pag);
                // summary_fields heurístico
                List<String> sfs = new ArrayList<>();
                try {
                    JsonNode first = node.get("items").size() > 0 ? node.get("items").get(0) : null;
                    if (first != null) {
                        if (first.has("nombre")) sfs.add("nombre");
                        if (first.has("email")) sfs.add("email");
                        if (sfs.isEmpty() && first.has("id")) sfs.add("id");
                    }
                } catch (Exception ignore) {}
                if (!sfs.isEmpty()) {
                    com.fasterxml.jackson.databind.node.ArrayNode sf = om.createArrayNode();
                    for (String k : sfs) sf.add(k);
                    out.set("summary_fields", sf);
                }
                // suggestions
                com.fasterxml.jackson.databind.node.ArrayNode sugg = om.createArrayNode();
                boolean hasMore = node.path("has_more").asBoolean(false) || node.has("next_page");
                if (hasMore) sugg.add("Siguiente página");
                if (node.has("prev_page") && !node.get("prev_page").isNull()) sugg.add("Anterior");
                sugg.add("Exportar a CSV");
                sugg.add("Exportar a Excel");
                out.set("suggestions", sugg);
                return buildEnvelopeFromContentNode(out);
            }

            // Detectar listado de academias: API devuelve { ok: true, result: [ ... ] }
            if (endpoint.toLowerCase(Locale.ROOT).contains("academias") && node.has("result") && node.get("result").isArray()) {
                com.fasterxml.jackson.databind.node.ObjectNode out = om.createObjectNode();
                int n = node.get("result").size();
                out.put("text", n > 0 ? "He obtenido el listado de academias disponibles." : "No hay academias.");
                out.set("academias", node.get("result"));
                // summary_fields
                com.fasterxml.jackson.databind.node.ArrayNode sf = om.createArrayNode();
                sf.add("id");
                sf.add("nombre");
                out.set("summary_fields", sf);
                // suggestions
                com.fasterxml.jackson.databind.node.ArrayNode sugg = om.createArrayNode();
                sugg.add("Consultar detalles de una academia");
                sugg.add("Crear una nueva academia");
                sugg.add("Eliminar una academia");
                sugg.add("Modificar una academia existente");
                out.set("suggestions", sugg);
                return buildEnvelopeFromContentNode(out);
            }

            return null;
        } catch (Exception e) {
            // En caso de cualquier problema, devolver null para seguir con el flujo normal
            return null;
        }
    }
    
    // Fallback final: si el mensaje sale vacío y no hay items, devolvemos un saludo útil y sugerencias por defecto
    private ResponseEnvelope applyFinalFallback(ResponseEnvelope env) {
        try {
            if (env != null && "success".equals(env.getStatus())) {
                String msg = env.getMessage() == null ? "" : env.getMessage().trim();
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
                if (msg.isEmpty() && noItems) {
                    env.setMessage("Hola, ¿en qué puedo ayudarte hoy?");
                    if (env.getSuggestions() == null || env.getSuggestions().isEmpty()) {
                        env.setSuggestions(List.of(
                            "Ver academias",
                            "Buscar profesores",
                            "Buscar alumnos",
                            "Ver mis cursos"
                        ));
                    }
                }
            }
        } catch (Exception ignore) { }
        return env;
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
        // suggestions
        List<String> suggestions = new ArrayList<>();
        JsonNode sNode = contentNode.get("suggestions");
        if (sNode != null && sNode.isArray()) {
            for (JsonNode s : sNode) if (s.isTextual()) suggestions.add(s.asText());
        }
        // No auto-generar suggestions: si la IA no las envía, se quedan vacías
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
        ResponseEnvelope env = ResponseEnvelope.success(message, data, suggestions, List.of());
        if (debug) {
            try {
                String prettyEnv = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(env);
                System.out.println("[ChatService][DEBUG] buildEnvelopeFromContentNode(type=" + typeDetected + ", items=" + items.size() + ") =>\n" + prettyEnv);
            } catch (Exception ignore) {}
        }
        return env;
    }

    // Inferir tipo de recurso a partir del nombre del endpoint usado en tool_call
    @SuppressWarnings("unused")
    private String inferTypeFromEndpoint(String endpointName) {
        if (endpointName == null) return "object";
        String e = endpointName.toLowerCase(Locale.ROOT);
        if (e.contains("usuarios")) return "usuarios";
        if (e.contains("academias")) return "academias";
        if (e.contains("cursos")) return "cursos";
        if (e.contains("alumnos")) return "alumnos";
        if (e.contains("profesores")) return "profesores";
        return "object"; // fallback estándar
    }

    // Se eliminaron las utilidades de renderizado Markdown/ficha para devolver siempre JSON/texto-encapsulado
}
