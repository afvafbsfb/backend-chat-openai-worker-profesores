
package com.workers.profesores.chat.service;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.dto.ChatRequest;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ChatService {
    private final OpenAICallApiService openai;
    private final ApiProxyService apiProxy;
    private final ObjectMapper om = new ObjectMapper();

    public ChatService(OpenAICallApiService openai, ApiProxyService apiProxy) {
        this.openai = openai;
        this.apiProxy = apiProxy;
    }


    public String runChat(List<ChatRequest.Message> incoming) {
        try {
                // 0) System prompt base + whitelist dinámica
                String promptBase = """
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

                     ⚠️ IMPORTANTE: NUNCA juntes varios registros en una sola línea de la tabla. Cada registro debe ir en su propia línea, igual que en el ejemplo anterior. Si hay muchos registros, sigue el mismo formato, uno por línea.

                     Si el usuario pide el listado en formato Excel o CSV, genera el listado en formato CSV (texto plano, separado por comas) y explica que puede copiar ese texto y pegarlo en Excel o guardarlo como archivo .csv para abrirlo en Excel o Google Sheets.

                     No digas que no puedes generar archivos Excel: ofrece siempre el CSV como alternativa y explica cómo usarlo.

                     Cuando muestres la tabla o los datos, utiliza frases naturales y amables, como:
                     - "Aquí tienes la lista de alumnos:"
                     - "Este es el listado solicitado:"
                     - "Te muestro la información en formato tabla:"
                     - "Listado de resultados:"
                     Evita mencionar palabras técnicas como 'Markdown'.
                     """;
            String whitelistTable = openai.renderWhitelistTable();
            String systemPrompt = promptBase + whitelistTable;

            Map<String, Object> systemMsg = Map.of(
                "role", "system",
                "content", systemPrompt
            );

            // 1) Construimos los mensajes a enviar:
            //    - Prependemos SIEMPRE nuestro system dinámico
            //    - (Opcional) ignoramos cualquier system previo en 'incoming' para evitar conflictos
            List<Map<String, Object>> seed = new ArrayList<>();
            seed.add(systemMsg);
            for (ChatRequest.Message m : incoming) {
                String role = m.getRole();
                if ("system".equals(role)) continue; // evitar system duplicado/contradictorio
                seed.add(Map.of(
                    "role", m.getRole(),
                    "content", m.getContent()
                ));
            }

            // 2) Primer turno al modelo (puede devolver tool_calls)
            JsonNode first = om.readTree(openai.callChatWithTools(seed));
            JsonNode choice = first.path("choices").get(0);
            JsonNode assistantMsg = choice.path("message");

            if (!assistantMsg.has("tool_calls")) {
                // Sin tool calls → respuesta directa
                String content = assistantMsg.path("content").asText("");
                return fixMarkdownTable(content);
            }

            // 3) Resolver tool_calls
            List<Map<String, Object>> toolOutputs = new ArrayList<>();
            for (JsonNode tc : assistantMsg.path("tool_calls")) {
                String callId   = tc.path("id").asText();
                String funcName = tc.path("function").path("name").asText();
                String argsStr  = tc.path("function").path("arguments").asText("{}");
                JsonNode args   = om.readTree(argsStr);

                if (!"call_api".equals(funcName)) {
                    toolOutputs.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", "{\"error\":\"Tool no permitida: " + funcName + "\"}"
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
                    continue;
                }

                String methodFromModel = args.path("method").asText("GET");
                JsonNode pathParams    = args.path("pathParams");
                JsonNode query         = args.path("query");
                JsonNode body          = args.path("body");

                String apiResult;
                try {
                    apiResult = apiProxy.executeWhitelistedCall(ep, methodFromModel, pathParams, query, body);
                } catch (Exception ex) {
                    apiResult = "{\"error\":\"Fallo al llamar API: " + ex.getMessage() + "\"}";
                }

                toolOutputs.add(Map.of(
                    "role", "tool",
                    "tool_call_id", callId,
                    "content", apiResult
                ));
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

            JsonNode second = om.readTree(openai.callChatWithTools(followup));
            JsonNode finalMsg = second.path("choices").get(0).path("message");
            return fixMarkdownTable(finalMsg.path("content").asText(""));

        } catch (Exception e) {
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
            for (String row : allRows) {
                cleanRows.append(row).append("\n");
            }
            String fixedTable = header + "\n" + separator + "\n" + cleanRows.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(fixedTable));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
