package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.workers.profesores.chat.dto.response.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

@Service
public class ChatService {
    private final OpenAICallApiService openai;
    private final ApiProxyService apiProxy;
    private final ObjectMapper om = new ObjectMapper();
    private final com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService;
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${backend.debug:false}")
    private boolean debug;
    // Note: local welcome handling removed to always delegate to OpenAI

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy, com.workers.profesores.chat.auth.JwtDelegationService jwtDelegationService) {
        this.openai = openai;
        this.apiProxy = apiProxy;
        this.jwtDelegationService = jwtDelegationService;
    }


    public ResponseEnvelope runChat(List<ChatRequest.Message> incoming, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization, com.workers.profesores.chat.auth.UserClaims claims) {
        try {
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
            // Prefetch user profile (so model has user's name available) and añadir contexto resumido del usuario (UserClaims)
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
            try {
                // Try canonical friendly name first; if not found try operationId used in the API
                Map<String, Object> epProfile = openai.getEndpointByName("getMiPerfil");
                if (epProfile == null) {
                    // The served-openapi often exposes operationId like 'usuarios.obtener_mi_perfil'
                    epProfile = openai.getEndpointByName("usuarios.obtener_mi_perfil");
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
            systemPromptSb.append(
                "Reglas de salida (estrictas):\n" +
                "- Devuelve UNICAMENTE un objeto JSON válido (sin texto adicional).\n" +
                "- Debe incluir siempre 'text' (string) con un breve resumen.\n" +
                "- Cuando el usuario pida listados (usuarios, academias, etc.), debes DEVOLVER SIEMPRE un array con la entidad solicitada: \n" +
                "  - Por ejemplo: 'usuarios': [ ... ] o 'academias': [ ... ].\n" +
                "  - Si no hay registros en base de datos, devuelve el array vacío [].\n" +
                "  - Copia los elementos tal cual los devuelve la API (mismos nombres de campos). No re-formatees las propiedades internas.\n" +
                "- Si el endpoint utilizado está marcado como paginado en la whitelist, añade obligatoriamente un objeto 'pagination' con los metadatos disponibles:\n" +
                "  { 'page': number, 'size': number, 'returned': number, 'has_more': boolean, 'next_page': number|null, 'prev_page': number|null, 'total': number|null }\n" +
                "  - Toma estos valores de la respuesta real de la API. No inventes datos; si un campo no viene, usa null o omítelo.\n" +
                "  - El campo 'returned' debe ser la longitud del array devuelto en la respuesta (p. ej. usuarios.length).\n" +
                "- Si el endpoint no es paginado pero devuelve una lista (array), incluye el array y omite 'pagination'.\n" +
                "- Puedes incluir 'suggestions' (array de strings) cuando proceda; por ejemplo, si 'has_more' es true: ['Siguiente página', 'Exportar a CSV', 'Exportar a Excel'].\n" +
                "- No uses Markdown ni tablas en la salida.\n"
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
                System.out.println("[ChatService][DEBUG] Raw OpenAI first response string: " + (rawFirst == null ? "<null>" : (rawFirst.length() > 1000 ? rawFirst.substring(0, 1000) + "..." : rawFirst)));
            }
            JsonNode first;
            try {
                first = om.readTree(rawFirst == null ? "" : rawFirst);
            } catch (Exception e) {
                // If parsing fails, wrap the raw response into a JSON object with property 'text'
                if (debug) System.out.println("[ChatService][DEBUG] OpenAI response not JSON, wrapping into text: " + (rawFirst == null ? "<null>" : rawFirst));
                return ResponseEnvelope.success("Respuesta modelo", DataSection.of("chat", List.of(), null), List.of(), List.of(MessageEntry.of("debug","raw_first_not_json")));
            }
            if (debug) {
                logger.debug("[ChatService] Parsed OpenAI first response into JSON");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "OpenAI primera respuesta parseada a JSON");
            }
            // Defensive: ensure choices array exists and has at least one element
            JsonNode choicesNode = first.path("choices");
            if (choicesNode == null || !choicesNode.isArray() || choicesNode.size() == 0) {
                // If the response already looks like a final content object with 'text', return it
                String msg = first.has("text") ? first.path("text").asText("") : first.toString();
                return ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of());
            }
            JsonNode choice = choicesNode.get(0);
            JsonNode assistantMsg = (choice == null) ? null : choice.path("message");
            if (assistantMsg == null || assistantMsg.isMissingNode()) {
                String msg = first.has("text") ? first.path("text").asText("") : first.toString();
                if (debug) System.out.println("[ChatService][DEBUG] choice.message missing, returning envelope.");
                return ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of());
            }

            if (!assistantMsg.has("tool_calls")) {
                String content = assistantMsg.path("content").asText("");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "No hay tool_calls, respuesta directa de OpenAI");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] No tool_calls, initial content: " + content);
                }
                // Si el contenido ya es JSON válido, procesarlo y convertirlo a envelope.
                try {
                    JsonNode contentNode = om.readTree(content);
                    return buildEnvelopeFromContentNode(contentNode);
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
                        String reformatInstruction = "Por favor, devuelve únicamente un objeto JSON válido con al menos la propiedad 'text' (string). Opcionalmente puedes incluir 'suggestions' (array de strings), 'academia' (objeto) o 'academias' (array). No incluyas explicaciones ni texto fuera del JSON. " +
                            "Si ya había sugerencias inclúyelas en 'suggestions'. Si mencionas el nombre del usuario, ponlo dentro de 'text' o como parte del texto.";
                        reformatSeed.add(Map.of("role", "user", "content", reformatInstruction));
                        String reformatted = openai.callChatWithTools(reformatSeed, xmlLogger, authorization);
                        if (debug) System.out.println("[ChatService][DEBUG] Reformat response from OpenAI: " + (reformatted == null ? "<null>" : (reformatted.length() > 1000 ? reformatted.substring(0,1000) + "..." : reformatted)));
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
                            return ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of());
                        }
                    } catch (Exception ex) {
                        if (debug) System.out.println("[ChatService][DEBUG] Exception while reformatting content: " + ex.getMessage());
                        return ResponseEnvelope.success(content, DataSection.of("chat", List.of(), null), List.of(), List.of());
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
            String singleObjectJson = null; // si la API devuelve un solo objeto, lo procesaremos pero siempre convirtiendo a envelope homogéneo
            String lastEndpointName = null; // para inferir tipo de recurso en single object con result[]
            
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
                if (!"call_api".equals(funcName)) {
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
                String endpointName = args.path("name").asText();
                lastEndpointName = endpointName;
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
                if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Llamada a API: " + endpointName + " (" + methodFromModel + ")");
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
                try {
                    om.readTree(apiResult);
                } catch (Exception parseEx) {
                    try {
                        Map<String, String> err = Map.of("error", apiResult == null ? "" : apiResult);
                        apiResult = om.writeValueAsString(err);
                        if (debug) System.out.println("[ChatService][DEBUG] Normalized non-JSON API result into JSON error object.");
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "Normalized non-JSON API result into JSON error object");
                    } catch (Exception wrapEx) {
                        String safe = apiResult == null ? "" : apiResult.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                        apiResult = "{\"error\":\"" + safe + "\"}";
                        if (debug) System.out.println("[ChatService][DEBUG] Fallback normalization applied to API result.");
                    }
                }

                // Detecta si la respuesta de la API es un solo objeto (no array, no error)
                // Pero evita tratar como "single object" respuestas paginadas (contienen totalElements o items)
                try {
                    JsonNode apiNode = om.readTree(apiResult);
                    if (apiNode != null && apiNode.isObject() && !apiNode.has("error")) {
                        boolean looksPaginated = apiNode.has("totalElements") || apiNode.has("items") || apiNode.has("page") || apiNode.has("size");
                        if (!looksPaginated) {
                            // Guardamos la representación JSON cruda para devolverla tal cual al cliente móvil
                            singleObjectJson = apiResult;
                            if (xmlLogger != null) xmlLogger.addStep("ChatService", "API devolvió un solo objeto, se retornará JSON crudo");
                            if (debug) {
                                System.out.println("[ChatService][DEBUG] API returned single object, will return raw JSON.");
                            }
                        } else {
                            if (xmlLogger != null) xmlLogger.addStep("ChatService", "API parece paginada o contener items; no se devuelve objeto único inmediatamente");
                            if (debug) {
                                System.out.println("[ChatService][DEBUG] API response looks paginated or contains items; deferring rendering.");
                            }
                        }
                    }
                } catch (Exception e) {
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] Exception parsing API result as JSON: " + e.getMessage());
                    }
                }
                toolOutputs.add(Map.of(
                    "role", "tool",
                    "tool_call_id", callId,
                    "content", apiResult
                ));
            }


            // Si la respuesta es un solo objeto (no paginada), devolvemos el JSON crudo directamente
            if (singleObjectJson != null) {
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Procesando singleObjectJson para envelope homogéneo");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Handling singleObjectJson (homogenize).");
                }
                try {
                    JsonNode node = om.readTree(singleObjectJson);
                    // Caso especial: { ok: true, result: [...] } => listado de recurso inferido
                    if (node.has("ok") && node.path("ok").isBoolean() && node.path("ok").asBoolean(true) && node.has("result") && node.path("result").isArray()) {
                        JsonNode arr = node.path("result");
                        List<JsonNode> items = new ArrayList<>();
                        for (JsonNode el : arr) items.add(el);
                        String inferredType = inferTypeFromEndpoint(lastEndpointName);
                        DataSection data = DataSection.of(inferredType, items, null);
                        String message = "Listado " + inferredType;
                        ResponseEnvelope env = ResponseEnvelope.success(message, data, List.of(), List.of());
                        if (debug) {
                            try { System.out.println("[ChatService][DEBUG] Envelope(singleObject->list type=" + inferredType + ")=" + om.writeValueAsString(env)); } catch (Exception ignore) {}
                        }
                        return env;
                    }
                    // Otro objeto simple: devolver como item único dentro de 'object'
                    List<JsonNode> items = new ArrayList<>();
                    items.add(node);
                    DataSection data = DataSection.of("object", items, null);
                    ResponseEnvelope env = ResponseEnvelope.success("Objeto devuelto", data, List.of(), List.of());
                    if (debug) {
                        try { System.out.println("[ChatService][DEBUG] Envelope(singleObjectSimple)=" + om.writeValueAsString(env)); } catch (Exception ignore) {}
                    }
                    return env;
                } catch (Exception ex) {
                    ResponseEnvelope env = ResponseEnvelope.success(singleObjectJson, DataSection.of("chat", List.of(), null), List.of(), List.of(MessageEntry.of("debug","raw_single_object_unparsed")));
                    if (debug) {
                        try { System.out.println("[ChatService][DEBUG] Envelope(singleObjectParseError)=" + om.writeValueAsString(env)); } catch (Exception ignore) {}
                    }
                    return env;
                }
            }


            // 4) Segundo turno: reinyectamos el assistant con sus tool_calls + los outputs
            List<Map<String, Object>> followup = new ArrayList<>(seed);
            Map<String, Object> assistantEcho = new HashMap<>();
            assistantEcho.put("role", "assistant");
            assistantEcho.put("content",
                assistantMsg.path("content").isMissingNode() ? "" : assistantMsg.path("content").asText("")
            );
            assistantEcho.put("tool_calls", om.convertValue(assistantMsg.path("tool_calls"), List.class));
            followup.add(assistantEcho);
            followup.addAll(toolOutputs);
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Segunda llamada a OpenAI (reinyectando resultados de tools)");
            if (debug) {
                System.out.println("[ChatService][DEBUG] Calling OpenAI with followup messages: " + followup);
            }
            JsonNode second = om.readTree(openai.callChatWithTools(followup, xmlLogger, authorization));
            if (debug) {
                System.out.println("[ChatService][DEBUG] OpenAI second response: " + second);
            }
            JsonNode finalMsg = second.path("choices").get(0).path("message");
            String finalContent = finalMsg.path("content").asText("");
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Respuesta final generada por OpenAI");
            if (debug) {
                System.out.println("[ChatService][DEBUG] Final content before returning: " + finalContent);
            }
            // Si finalContent es JSON válido, devolverlo tal cual; si no, envolver en {"text": ...}
            try {
                JsonNode node = om.readTree(finalContent);
                ResponseEnvelope env = buildEnvelopeFromContentNode(node);
                if (debug) {
                    try { System.out.println("[ChatService][DEBUG] Envelope(final_no_toolcalls)=" + om.writeValueAsString(env)); } catch (Exception ignore) {}
                }
                return env;
            } catch (Exception e) {
                ResponseEnvelope env = ResponseEnvelope.success(finalContent, DataSection.of("chat", List.of(), null), List.of(), List.of());
                if (debug) {
                    try { System.out.println("[ChatService][DEBUG] Envelope(final_plain_text)=" + om.writeValueAsString(env)); } catch (Exception ignore) {}
                }
                return env;
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
    // Nuevo método privado para construir Envelope desde un JsonNode del modelo
    private ResponseEnvelope buildEnvelopeFromContentNode(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return ResponseEnvelope.success("", DataSection.of("chat", List.of(), null), List.of(), List.of());
        }
        // Detect arrays representando recursos
        List<String> resourceKeys = List.of("usuarios","academias","cursos","alumnos","profesores");
        String typeDetected = "chat";
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
        // Si no encontró array de recursos pero el nodo es objeto => devolverlo como único item opcionalmente
        if (items.isEmpty() && contentNode.isObject()) {
            // Si tiene propiedad 'text' usar solo mensaje
            if (contentNode.has("text") && contentNode.size() == 1) {
                String msg = contentNode.get("text").asText("");
                return ResponseEnvelope.success(msg, DataSection.of("chat", List.of(), null), List.of(), List.of());
            }
        }
        String message = contentNode.path("text").asText("");
        if (message.isEmpty()) {
            message = (contentNode.toString().length() > 180) ? contentNode.toString().substring(0,180)+"..." : contentNode.toString();
        }
        // suggestions
        List<String> suggestions = new ArrayList<>();
        JsonNode sNode = contentNode.get("suggestions");
        if (sNode != null && sNode.isArray()) {
            for (JsonNode s : sNode) if (s.isTextual()) suggestions.add(s.asText());
        }
        if (suggestions.isEmpty() && pagination != null && Boolean.TRUE.equals(pagination.getHasMore())) {
            suggestions = List.of("Siguiente página","Exportar a CSV","Exportar a Excel");
        }
        DataSection data = DataSection.of(typeDetected, items, pagination);
        ResponseEnvelope env = ResponseEnvelope.success(message, data, suggestions, List.of());
        if (debug) {
            try {
                System.out.println("[ChatService][DEBUG] buildEnvelopeFromContentNode(type=" + typeDetected + ", items=" + items.size() + ") => " + om.writeValueAsString(env));
            } catch (Exception ignore) {}
        }
        return env;
    }

    // Inferir tipo de recurso a partir del nombre del endpoint usado en tool_call
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
