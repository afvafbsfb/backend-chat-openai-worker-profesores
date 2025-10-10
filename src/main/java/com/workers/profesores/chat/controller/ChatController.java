
package com.workers.profesores.chat.controller;

import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.service.ChatService;
import com.workers.profesores.chat.auth.JwtVerifier;
import com.workers.profesores.chat.auth.UserClaims;
import com.workers.profesores.chat.util.RequestFlowXmlLogger;
import com.workers.profesores.chat.util.RequestFlowXmlContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "http://localhost:5173", allowedHeaders = "*", allowCredentials = "true")
public class ChatController {
    private final ChatService chatService;
    private final JwtVerifier jwtVerifier;

    // Flag para activar/desactivar modo debug global
    @Value("${backend.debug:false}")
    private boolean debug;

    public ChatController(ChatService chatService, JwtVerifier jwtVerifier) {
        this.chatService = chatService;
        this.jwtVerifier = jwtVerifier;
    }

    @PostMapping
    public ResponseEntity<String> chat(@RequestBody ChatRequest request, @RequestHeader(value = "X-Flow-Diagram", required = false) String flowDiagram,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        // Solo crear el logger HTML si la cabecera X-Flow-Diagram=true está presente
        RequestFlowXmlLogger xmlLogger = null;
        String requestId = null;
        String result = null;
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
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Recibida petición POST /chat");
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Payload recibido: " + (request != null ? request.toString() : "null"));
            }
            if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
                if (xmlLogger != null) {
                    xmlLogger.addStep("ChatController", "Request vacío. Se responde 400");
                }
                if (debug) {
                    System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Request vacío. Se responde 400");
                }
                return ResponseEntity.badRequest().body("Request vacío");
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
                        System.out.println("[ChatController][DEBUG] Incoming Authorization preview=" + preview + ", token_sha4=" + sb.toString());
                    } catch (Exception ignore) { }
                }
                claims = jwtVerifier.verify(authorization);
            } catch (Exception ex) {
                if (xmlLogger != null) xmlLogger.addStep("ChatController", "JWT inválido: " + ex.getMessage());
                return ResponseEntity.status(401).body("Token inválido o expirado");
            }
            result = chatService.runChat(request.getMessages(), xmlLogger, authorization, claims);
            if (xmlLogger != null) {
                String outputMsg = (result != null && !result.isEmpty()) ? ("<br>Mensaje de salida: " + result) : "";
                xmlLogger.addStep("ChatController", "Respuesta generada por ChatService" + outputMsg);
            }
            if (debug) {
                System.out.println("DEBUG " + LocalDateTime.now() + " [ChatController][chat] Respuesta generada por ChatService: " + (result != null ? result : ""));
            }
            return ResponseEntity.ok(result != null ? result : "");
        } catch (Exception ex) {
            if (xmlLogger != null) {
                xmlLogger.addStep("ChatController", "Excepción: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            if (debug) {
                ex.printStackTrace();
            }
            return ResponseEntity.status(500).body("Error interno del servidor: " + ex.getMessage());
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
