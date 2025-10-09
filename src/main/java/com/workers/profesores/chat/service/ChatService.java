package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

@Service
public class ChatService {
    private final OpenAICallApiService openai;
    private final ApiProxyService apiProxy;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${backend.debug:false}")
    private boolean debug;
    // Note: local welcome handling removed to always delegate to OpenAI

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy) {
        this.openai = openai;
        this.apiProxy = apiProxy;
    }


    public String runChat(List<ChatRequest.Message> incoming, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger, String authorization, com.workers.profesores.chat.auth.UserClaims claims) {
        try {
            if (debug) {
                System.out.println("[ChatService][DEBUG] runChat called with incoming: " + incoming);
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Inicio de runChat");
            // 0) System prompt base + whitelist dinámica
            String promptBase = "Eres un asistente (secretaria) para una plataforma de academias en España. Solo puedes acceder a los recursos de la API mediante la función call_api y siempre bajo las condiciones de autorizacion que tenga el rol del usuario logueado. Cuando necesites datos, usa exclusivamente call_api con los endpoints permitidos. Responde en castellano, de forma breve y clara. Si necesitas confirmar una operación destructiva, pide confirmación explícita antes de ejecutar. Cuando pidas listados grandes, sugiere exportar a CSV/Excel en lugar de mostrar miles de filas. No uses tablas Markdown en el system prompt ni en las instrucciones del sistema.";
                String whitelistTable = openai.renderEndpointsTable();
            // Prefetch user profile (so model has user's name available) and añadir contexto resumido del usuario (UserClaims)
            String profileJsonForPrompt = "{}";
            String userNameForPrompt = null;
            try {
                Map<String, Object> epProfile = openai.getEndpointByName("getMiPerfil");
                if (epProfile != null) {
                    // executeWhitelistedCall normaliza a JSON string
                    profileJsonForPrompt = apiProxy.executeSpecCall(epProfile, "GET", null, null, null, authorization);
                    try {
                        JsonNode pnode = om.readTree(profileJsonForPrompt);
                        if (pnode.has("nombre")) userNameForPrompt = pnode.path("nombre").asText(null);
                        else if (pnode.has("name")) userNameForPrompt = pnode.path("name").asText(null);
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
            systemPromptSb.append("Reglas de salida: cuando generes la respuesta final para el cliente móvil, devuelve UNICAMENTE un objeto JSON válido (sin texto adicional) con al menos la propiedad 'text' (string). Opcionalmente puedes incluir 'suggestions' (array de strings), 'academia' (objeto) o 'academias' (array). Si necesitas datos adicionales (por ejemplo perfil del usuario o detalles de academias) realiza una tool_call a call_api con el nombre del endpoint correspondiente.\n");
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
                System.out.println("[ChatService][DEBUG] Calling OpenAI with seed messages...");
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (callChatWithTools)");
            // Pasamos el Authorization (Bearer token) a la llamada a OpenAI service para que las herramientas puedan acceder al token si es necesario
            String rawFirst = openai.callChatWithTools(seed, xmlLogger, authorization);
            if (debug) {
                System.out.println("[ChatService][DEBUG] Raw OpenAI first response string: " + (rawFirst == null ? "<null>" : (rawFirst.length() > 1000 ? rawFirst.substring(0, 1000) + "..." : rawFirst)));
            }
            JsonNode first = om.readTree(rawFirst);
            if (debug) {
                System.out.println("[ChatService][DEBUG] Parsed OpenAI first response into JSON");
            }
            JsonNode choice = first.path("choices").get(0);
            JsonNode assistantMsg = choice.path("message");

            if (!assistantMsg.has("tool_calls")) {
                String content = assistantMsg.path("content").asText("");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "No hay tool_calls, respuesta directa de OpenAI");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] No tool_calls, initial content: " + content);
                }
                // Si el contenido ya es JSON válido, devolverlo tal cual.
                try {
                    om.readTree(content);
                    return content;
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
                                try { om.readTree(rcStr); return rcStr; } catch (Exception exx) { /* fall through */ }
                            }
                        } catch (Exception ignore) {
                        }
                        // Si la respuesta no contenía 'choices' o no devolvió JSON directo, intentamos parsearla como JSON directa
                        try {
                            om.readTree(reformatted);
                            return reformatted;
                        } catch (Exception ex2) {
                            // Fallback: devolver el texto original envuelto en text
                            if (debug) System.out.println("[ChatService][DEBUG] Reformatting failed, returning fallback text JSON.");
                            return om.writeValueAsString(Map.of("text", content));
                        }
                    } catch (Exception ex) {
                        if (debug) System.out.println("[ChatService][DEBUG] Exception while reformatting content: " + ex.getMessage());
                        return om.writeValueAsString(Map.of("text", content));
                    }
                }
            }

            // 3) Resolver tool_calls

            List<Map<String, Object>> toolOutputs = new ArrayList<>();
            String singleObjectJson = null; // si la API devuelve un solo objeto, lo devolveremos tal cual en JSON
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
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] Calling ApiProxyService: endpoint=" + endpointName + ", method=" + methodFromModel + ", pathParams=" + pathParams + ", query=" + query + ", body=" + body);
                    }
                    String apiResult;
                try {
                    // Antes de ejecutar, validar permisos y sanitizar parámetros
                    try {
                        apiResult = apiProxy.executeSpecCall(ep, methodFromModel, pathParams, query, body, authorization);
                    } catch (Exception exInner) {
                        // Ensure apiResult is always a JSON string describing the error
                        Map<String, Object> errMap = Map.of("error", "authorization_failure", "message", exInner.getMessage() == null ? "" : exInner.getMessage());
                        apiResult = om.writeValueAsString(errMap);
                        if (xmlLogger != null) xmlLogger.addStep("Authorization", "Fallo autorización: " + exInner.getMessage());
                    }
                    if (xmlLogger != null) xmlLogger.addStep("ApiProxyService", "Respuesta recibida de API: " + endpointName);
                    if (debug) {
                        System.out.println("[ChatService][DEBUG] ApiProxyService result: " + apiResult);
                    }
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
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Respuesta directa: JSON de un solo objeto");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Returning singleObjectJson directly.");
                }
                return singleObjectJson;
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
                om.readTree(finalContent);
                return finalContent;
            } catch (Exception e) {
                return om.writeValueAsString(Map.of("text", finalContent));
            }

        } catch (Exception e) {
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Excepción en runChat: " + e.getMessage());
            if (debug) {
                System.out.println("[ChatService][DEBUG] Exception in runChat: " + e.getMessage());
                e.printStackTrace();
            }
            return "Error en runChat: " + e.getMessage();
        }
    }

    // Backward-compatible overload used by existing tests and callers that don't provide authorization/claims
    public String runChat(List<ChatRequest.Message> incoming, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) {
        return runChat(incoming, xmlLogger, null, null);
    }

    // Se eliminaron las utilidades de renderizado Markdown/ficha para devolver siempre JSON/texto-encapsulado
}
