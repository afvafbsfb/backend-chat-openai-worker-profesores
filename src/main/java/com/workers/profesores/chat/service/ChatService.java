package com.workers.profesores.chat.service;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

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

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy) {
        this.openai = openai;
        this.apiProxy = apiProxy;
    }


    public String runChat(List<ChatRequest.Message> incoming, com.workers.profesores.chat.util.RequestFlowXmlLogger xmlLogger) {
        try {
            if (debug) {
                System.out.println("[ChatService][DEBUG] runChat called with incoming: " + incoming);
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Inicio de runChat");
            // 0) System prompt base + whitelist dinámica
            String promptBase = """
                 
                 Cuando el usuario pida exportar una tabla completa a Excel o CSV, responde SOLO con un enlace de descarga al archivo, nunca muestres el contenido del archivo (ni CSV ni Excel) en pantalla ni como texto plano.
                 
                 ⚠️ Cuando el usuario pregunte únicamente por el número total de registros (por ejemplo: "¿Cuántos alumnos hay?", "¿Cuántos turnos existen?", "Dame el total de alumnos"), responde SOLO con el número total, sin mostrar la lista de registros ni una tabla. Si el usuario pide explícitamente la lista o el detalle junto al total (por ejemplo: "Dame la lista de alumnos y el total"), entonces sí muestra la tabla y el total juntos.
                 
                 Si la pregunta es ambigua, prioriza la brevedad: si solo se pide el total, responde solo el total; si se pide la lista, muestra la tabla y el total.
                 El enlace debe ser:
                 [Descargar CSV]({{API_BASE_URL}}/api/export?tabla=nombre_tabla&type=csv&parámetros_reales) o [Descargar Excel]({{API_BASE_URL}}/api/export?tabla=nombre_tabla&type=xlsx&parámetros_reales)
                 El enlace debe incluir SIEMPRE los parámetros REALES de filtro, paginación y orden (por ejemplo: &filter=valor, &page=0, &size=50, &sort=nombre, &order=asc), nunca uses puntos suspensivos ni literales.
                 Sustituye {{API_BASE_URL}} por la URL base real de la API (por ejemplo, http://localhost:8080 o la de producción).
                 Explica al usuario que puede descargar el archivo haciendo clic en el enlace.
                 NUNCA muestres el CSV completo ni ningún archivo exportado en el chat, solo el enlace de descarga.
                 Eres “secretaria”, asistente de una academia en España. Tu objetivo es ayudar a gestionar alumnos, matrículas, pagos y consultas sobre la API EXCLUSIVAMENTE usando la función `call_api` contra una lista blanca de endpoints.

                 Reglas:
                 1) Usa SOLO `call_api` con los endpoints permitidos. Si falta un parámetro (id, page, size, q...), PÍDEMELO antes de llamar.
                 2) Construye `call_api` con:
                     - name: nombre lógico del endpoint (según whitelist)
                     - method: EXACTO según whitelist
                     - pathParams/query/body: SOLO los campos necesarios.
                 3) Si la API devuelve error, explícalo de forma clara y sugiere corrección.
                 4) Para operaciones destructivas (crear/actualizar/borrar), PIDE CONFIRMACIÓN antes de ejecutar.
                 5) Optimiza: 1 sola llamada si es posible. Pagina resultados grandes (page/size).
                 6) Responde SIEMPRE en castellano, con brevedad y claridad; usa viñetas o tablas si ayudan.

                 Cuando devuelvas un listado de datos (alumnos, inscripciones, empresas, etc.), preséntalo SIEMPRE en formato tabla, usando líneas y columnas bien alineadas, de forma clara y fácil de leer para cualquier persona en España.
                 - Cada registro debe ocupar UNA SOLA FILA de la tabla (no todos en la misma línea).
                 - Los títulos de columna deben estar bien formateados y alineados.
                 - No añadas saltos de línea dentro de una misma celda.
                 - El formato debe ser compatible para copiar y pegar en Excel o Google Sheets.

                 Ejemplo de tabla correcta (cada registro en una línea, nunca todos juntos):
                 | ID  | Nombre     | Email                |
                 |-----|------------|----------------------|
                 | 1   | Alumno 1   | alumno1@ejemplo.com  |
                 | 2   | Alumno 2   | alumno2@ejemplo.com  |
                 | 3   | Alumno 3   | alumno3@ejemplo.com  |
                 | 4   | Alumno 4   | alumno4@ejemplo.com  |
                 | 5   | Alumno 5   | alumno5@ejemplo.com  |

                 ⚠️ IMPORTANTE: NUNCA juntes varios registros en una sola línea de la tabla. Cada registro debe ir en su propia línea, igual que en el ejemplo anterior. 
                 
                 Si hay muchos registros, sigue el mismo formato, uno por línea.

                 🔢 Manejo de listados grandes
                Si un endpoint devuelve más de 50 registros, mostrar solo los primeros 50 en tabla Markdown.
                Si hay más registros disponibles, añade un aviso:
                👉 “Se muestran solo los 50 primeros resultados. Pídeme ‘siguiente’ para ver más.”
                Si NO hay más registros, indícalo claramente con un mensaje como: “No hay más resultados.”
                Si existe soporte de paginación en la API (?page, ?limit), úsalo para devolver bloques de 50.
                Nunca mostrar miles de registros en un único bloque.

                ⚠️ IMPORTANTE sobre paginación:
                El endpoint `/vlodeiro/secretaria/alumnos` soporta paginación mediante los parámetros `page` (número de página, empezando en 0) y `size` (número de alumnos por página, por defecto 10, máximo 50). 
                Si el usuario no especifica estos parámetros, usa los valores por defecto. El resto de endpoints de momento no soportan paginación y devuelven todos los resultados disponibles.

                Si el usuario pide el listado en formato Excel o CSV, sigue SIEMPRE la instrucción de responder solo con el enlace de descarga al archivo exportado, nunca muestres el contenido del archivo en el chat.

                No digas que no puedes generar archivos Excel: ofrece siempre el enlace de descarga como alternativa.

                 Cuando muestres la tabla o los datos, utiliza frases naturales y amables, como:
                 - "Aquí tienes la lista de alumnos:"
                 - "Este es el listado solicitado:"
                 - "Te muestro la información en formato tabla:"
                 - "Listado de resultados:"
                 Evita mencionar palabras técnicas como 'Markdown'.

                 - Cuando muestres el detalle de un solo registro (alumno, empresa, inscripción, etc.), presenta SIEMPRE todos los campos relevantes (ID, nombre, email, etc.) en formato ficha, mostrando cada campo en una línea distinta, con el formato **Campo:** valor. Ejemplo:
                    **Email:** [alumno43@example.com](mailto:alumno43@example.com)
                    **Id:** 43
                    **Nombre:** Alumno 43
                    No uses tabla Markdown para un solo registro. No omitas nunca el email si está disponible en los datos.
            """;
            String whitelistTable = openai.renderWhitelistTable();
            String systemPrompt = promptBase + whitelistTable;
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "System prompt construido y whitelist añadida");
            if (debug) {
                System.out.println("[ChatService][DEBUG] System prompt constructed: " + systemPrompt);
            }

            Map<String, Object> systemMsg = Map.of(
                "role", "system",
                "content", systemPrompt
            );

            // 1) Construimos los mensajes a enviar:
            List<Map<String, Object>> seed = new ArrayList<>();
            seed.add(systemMsg);
            for (ChatRequest.Message m : incoming) {
                String role = m.getRole();
                if ("system".equals(role)) continue;
                seed.add(Map.of(
                    "role", m.getRole(),
                    "content", m.getContent()
                ));
            }
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Mensajes de usuario preparados para OpenAI");

            if (debug) {
                System.out.println("[ChatService][DEBUG] Seed messages for OpenAI: " + seed);
            }

            // 2) Primer turno al modelo (puede devolver tool_calls)

            if (debug) {
                System.out.println("[ChatService][DEBUG] Calling OpenAI with seed messages...");
            }
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Primera llamada a OpenAI (callChatWithTools)");
            JsonNode first = om.readTree(openai.callChatWithTools(seed, xmlLogger));
            if (debug) {
                System.out.println("[ChatService][DEBUG] OpenAI first response: " + first);
            }
            JsonNode choice = first.path("choices").get(0);
            JsonNode assistantMsg = choice.path("message");

            if (!assistantMsg.has("tool_calls")) {
                String content = assistantMsg.path("content").asText("");
                if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "No hay tool_calls, respuesta directa de OpenAI");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] No tool_calls, returning post-processed response.");
                }
                return postProcessResponse(fixMarkdownTable(content));
            }

            // 3) Resolver tool_calls

            List<Map<String, Object>> toolOutputs = new ArrayList<>();
            boolean singleObjectResponse = false;
            String fichaContent = null;
            for (JsonNode tc : assistantMsg.path("tool_calls")) {
                String callId   = tc.path("id").asText();
                String funcName = tc.path("function").path("name").asText();
                String argsStr  = tc.path("function").path("arguments").asText("{}");
                JsonNode args   = om.readTree(argsStr);
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
                    apiResult = apiProxy.executeWhitelistedCall(ep, methodFromModel, pathParams, query, body);
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
                // Detecta si la respuesta de la API es un solo objeto (no array, no error)
                try {
                    JsonNode apiNode = om.readTree(apiResult);
                    if (apiNode != null && apiNode.isObject() && !apiNode.has("error")) {
                        singleObjectResponse = true;
                        fichaContent = renderDetailAsFicha(apiNode);
                        if (xmlLogger != null) xmlLogger.addStep("ChatService", "API devolvió un solo objeto, se renderiza como ficha");
                        if (debug) {
                            System.out.println("[ChatService][DEBUG] API returned single object, will render as ficha.");
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


            // Si la respuesta es un solo objeto, devolvemos la ficha directamente (sin pasar por el modelo)
            if (singleObjectResponse && fichaContent != null) {
                if (xmlLogger != null) xmlLogger.addStep("ChatService", "Respuesta directa: ficha de un solo objeto");
                if (debug) {
                    System.out.println("[ChatService][DEBUG] Returning fichaContent directly (single object response).");
                }
                return fichaContent;
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
            JsonNode second = om.readTree(openai.callChatWithTools(followup, xmlLogger));
            if (debug) {
                System.out.println("[ChatService][DEBUG] OpenAI second response: " + second);
            }
            JsonNode finalMsg = second.path("choices").get(0).path("message");
            String finalContent = finalMsg.path("content").asText("");
            if (xmlLogger != null) xmlLogger.addStep("OpenAIClient", "Respuesta final generada por OpenAI");
            if (debug) {
                System.out.println("[ChatService][DEBUG] Final content before post-processing: " + finalContent);
            }
            return postProcessResponse(fixMarkdownTable(finalContent));

        } catch (Exception e) {
            if (xmlLogger != null) xmlLogger.addStep("ChatService", "Excepción en runChat: " + e.getMessage());
            if (debug) {
                System.out.println("[ChatService][DEBUG] Exception in runChat: " + e.getMessage());
                e.printStackTrace();
            }
            return "Error en runChat: " + e.getMessage();
        }
    }

    /**
     * Post-procesa la respuesta para arreglar tablas Markdown con registros en una sola línea.
     * Si detecta una tabla con varios registros en la misma línea, la re-formatea para que cada registro esté en su propia línea.
     */
    private String fixMarkdownTable(String content) {
        // Regex para detectar tablas Markdown (cabecera, separador, filas)
        Pattern pattern = Pattern.compile("(\\|[^\\n]+\\|)\\s*\\n(\\|[-:| ]+\\|)\\s*\\n((?:.*?\\|.*?)+)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String header = matcher.group(1).trim();
            String separator = matcher.group(2).trim();
            String rows = matcher.group(3).trim();
            int numCols = header.split("\\|").length - 2;
            List<String> allRows = new ArrayList<>();
            // Si las filas vienen todas pegadas en una sola línea, sepáralas
            if (!rows.contains("\n")) {
                // Quitar el primer y último |
                String registros = rows.substring(1, rows.length() - 1);
                String[] celdas = registros.split("\\|");
                for (int i = 0; i + numCols <= celdas.length; i += numCols) {
                    StringBuilder row = new StringBuilder("|");
                    boolean filaCompleta = true;
                    for (int j = 0; j < numCols; j++) {
                        if (i + j >= celdas.length) {
                            filaCompleta = false;
                            break;
                        }
                        row.append(celdas[i + j].trim()).append("|");
                    }
                    if (filaCompleta) {
                        allRows.add(row.toString());
                    }
                }
            } else {
                // Quitar líneas vacías y espacios
                String[] rawLines = rows.split("\\n");
                for (String line : rawLines) {
                    String l = line.trim();
                    if (l.isEmpty()) continue;
                    // Si la línea contiene varias filas pegadas, sepáralas
                    String[] parts = l.split("(?=\\| ?[0-9]+ ?\\|)");
                    for (String part : parts) {
                        String p = part.trim();
                        if (p.startsWith("|")) {
                            if (p.split("\\|").length - 2 == numCols) {
                                allRows.add(p);
                            }
                        }
                    }
                }
            }
            // Reconstruye la tabla
            StringBuilder cleanRows = new StringBuilder();
            Pattern markdownEmail = Pattern.compile("\\[([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\]\\(mailto:[^)]*\\)");
            Pattern htmlEmail = Pattern.compile("<a\\s+href=\\\"mailto:[^\\\"]+\\\">([^<]+)</a>");
            Pattern plainEmail = Pattern.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
            for (String row : allRows) {
                // Elimina enlaces Markdown y HTML, deja solo el texto plano del email
                String rowClean = markdownEmail.matcher(row).replaceAll("$1");
                rowClean = htmlEmail.matcher(rowClean).replaceAll("$1");
                cleanRows.append(rowClean).append("\n");
            }
            String fixedTable = header + "\n" + separator + "\n" + cleanRows.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(fixedTable));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // Utilidad: formatea un objeto JSON como ficha (campos uno debajo de otro)
    private String renderDetailAsFicha(JsonNode node) {
        if (node == null || !node.isObject()) return "";
        StringBuilder sb = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        Pattern markdownEmail = Pattern.compile("\\[([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\]\\(mailto:[^)]*\\)");
        Pattern htmlEmail = Pattern.compile("<a\\s+href=\\\"mailto:[^\\\"]+\\\">([^<]+)</a>");
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            String value = entry.getValue().asText("");
            // Elimina enlaces Markdown y HTML, deja solo el texto plano del email
            if (key.toLowerCase().contains("mail") && value.contains("@")) {
                value = markdownEmail.matcher(value).replaceAll("$1");
                value = htmlEmail.matcher(value).replaceAll("$1");
            }
            sb.append("**").append(capitalize(key)).append(":** ").append(value).append("\n");
        }
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // Detecta si la respuesta es un solo objeto y la muestra como ficha
    private String postProcessResponse(String content) {
        try {
            // Intenta parsear como JSON
            JsonNode node = om.readTree(content);
            if (node != null && node.isObject()) {
                // Es un solo objeto: mostrar como ficha
                return renderDetailAsFicha(node);
            }
        } catch (Exception e) {
            // No es JSON, devolver tal cual
        }
        // Si no es objeto, devolver el contenido original (tabla, texto, etc.)
        return content;
    }
}
