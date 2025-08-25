
package com.workers.profesores.chat.controller;

import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.service.ChatService;
import com.workers.profesores.chat.util.RequestFlowXmlLogger;
import com.workers.profesores.chat.util.RequestFlowXmlContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private final ChatService chatService;

    // Flag para activar/desactivar modo debug global
    @Value("${backend.debug:false}")
    private boolean debug;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<String> chat(@RequestBody ChatRequest request, @RequestHeader(value = "X-Flow-Diagram", required = false) String flowDiagram) {
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
            result = chatService.runChat(request.getMessages(), xmlLogger);
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
