package com.workers.profesores.chat.controller;

import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.service.ChatService;
import com.workers.profesores.chat.auth.JwtVerifier;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.util.RequestFlowXmlLogger;
import com.workers.profesores.chat.util.RequestFlowXmlContext;
import org.springframework.http.ResponseEntity;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "http://localhost:5173", allowedHeaders = "*", allowCredentials = "true")
public class ChatController {
    private final ChatService chatService;
    private final JwtVerifier jwtVerifier;
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    // Flag para activar/desactivar modo debug global
    @Value("${backend.debug:false}")
    private boolean debug;

    public ChatController(ChatService chatService, JwtVerifier jwtVerifier) {
        this.chatService = chatService;
        this.jwtVerifier = jwtVerifier;
    }

    @PostMapping
    public ResponseEntity<ResponseEnvelope> chat(@RequestBody ChatRequest request, @RequestHeader(value = "X-Flow-Diagram", required = false) String flowDiagram,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        // Solo crear el logger HTML si la cabecera X-Flow-Diagram=true está presente
        RequestFlowXmlLogger xmlLogger = null;
        String requestId = null;
        boolean loggerActivo = false;
        try {
            if (flowDiagram != null && flowDiagram.equalsIgnoreCase("true")) {
                String testName = "prueba-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
                String htmlPath = "documentacion";
                xmlLogger = new RequestFlowXmlLogger(testName, htmlPath);
                requestId = java.util.UUID.randomUUID().toString();
                RequestFlowXmlContext.set(requestId, xmlLogger);
                loggerActivo = true;
                String userMsg = "";
                if (request != null && request.getMessages() != null && !request.getMessages().isEmpty()) {
                    ChatRequest.Message firstMsg = request.getMessages().get(0);
                    userMsg = "Mensaje recibido: [" + firstMsg.getRole() + "] " + firstMsg.getContent();
                }
                xmlLogger.addStep("ChatController", "Recibida petición POST /chat" + (userMsg.isEmpty() ? "" : ("<br>" + userMsg)));
            }
            if (debug) {
                String trace = requestId == null ? "" : (" requestId=" + requestId);
                logger.debug("[ChatController][chat] Recibida petición POST /chat{}", trace);
                logger.debug("[ChatController][chat] Payload recibido: {}{}", (request != null ? request.toString() : "null"), trace);
            }
            if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
                if (xmlLogger != null) {
                    xmlLogger.addStep("ChatController", "Request vacío. Se responde 400");
                }
                if (debug) {
                    System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Request vacío. Se responde 400");
                }
                return ResponseEntity.badRequest().body(ResponseEnvelope.error("Petición inválida","bad_request","Request vacío", List.of()));
            }
            // Verificar JWT y extraer claims
            UserClaims claims = null;
            try {
                if (debug && authorization != null) {
                    try {
                        String a = authorization.trim();
                        String preview = a.length() > 12 ? a.substring(0, 8) + "..." : a;
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(a.getBytes(StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4 && i < digest.length; i++) sb.append(String.format("%02x", digest[i]));
                        logger.debug("[ChatController][DEBUG] Incoming Authorization preview={} , token_sha4={}", preview, sb.toString());
                    } catch (Exception ignore) { }
                }
                claims = jwtVerifier.verify(authorization);
                // Log claims minimal info and add to html trace
                if (debug && claims != null) {
                    String roles = claims.roles == null ? "[]" : claims.roles.toString();
                    String academia = claims.academiaId == null ? "null" : String.valueOf(claims.academiaId);
                    String userId = claims.usuarioId == null ? "null" : String.valueOf(claims.usuarioId);
                    String trace = requestId == null ? "" : (" requestId=" + requestId);
                    logger.debug("[ChatController][JWT] usuarioId={} roles={} academiaId={}{}", userId, roles, academia, trace);
                    if (xmlLogger != null) xmlLogger.addStep("ChatController", "JWT válido: usuarioId=" + userId + ", roles=" + roles + ", academiaId=" + academia);
                }
            } catch (Exception ex) {
                if (xmlLogger != null) xmlLogger.addStep("ChatController", "JWT inválido: " + ex.getMessage());
                return ResponseEntity.status(401).body(ResponseEnvelope.error("No autorizado","unauthorized","Token inválido o expirado", List.of()));
            }

            // Generar y almacenar el token delegado
                // Do not generate delegated token here. ChatService will create it on-demand
                // and reuse it for all proxied calls within the chat session.
            if (debug) {
                System.out.println("DEBUG: Delegated token generation moved to ChatService (on demand).");
            }

            // Log para el token delegado
            if (debug && claims != null) {
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Generando token delegado para el usuario: " + claims.usuarioId);
            }

            // Log para llamada al API de academias
            if (debug && request.getMessages().stream().anyMatch(msg -> msg.getContent().contains("academia"))) {
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Llamando al API de academias con token delegado.");
            }

            ResponseEnvelope envelope = chatService.runChat(request.getMessages(), xmlLogger, authorization, claims);
            // Serializar envelope para log en pretty-print (no afecta a HTTP)
            String envelopePretty = "";
            try {
                ObjectMapper mapper = new ObjectMapper();
                envelopePretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
            } catch (Exception serEx) {
                envelopePretty = "{\"error\":\"No se pudo serializar envelope: " + serEx.getMessage() + "\"}";
            }
            if (xmlLogger != null) {
                xmlLogger.addStep("ChatController", "Respuesta generada por ChatService (pretty)=\n" + envelopePretty);
            }
            if (debug) {
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Respuesta generada por ChatService (JSON completo, pretty):\n" + envelopePretty);
                logger.debug("[ChatController][chat] Envelope pretty chars={}", envelopePretty.length());
            }
            return ResponseEntity.ok(envelope);
        } catch (Exception ex) {
            if (xmlLogger != null) {
                xmlLogger.addStep("ChatController", "Excepción: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            if (debug) {
                ex.printStackTrace();
            }
            return ResponseEntity.status(500).body(ResponseEnvelope.error("Error interno","internal_error", ex.getMessage(), List.of()));
        } finally {
            if (xmlLogger != null && loggerActivo) {
                try {
                    xmlLogger.writeHtml();
                } catch (Exception ex2) {
                    if (debug) {
                        System.out.println("[ChatController][chat] Error al escribir HTML: " + ex2.getMessage());
                    }
                }
                if (requestId != null) {
                    RequestFlowXmlContext.remove(requestId);
                }
            }
        }
    }
}
