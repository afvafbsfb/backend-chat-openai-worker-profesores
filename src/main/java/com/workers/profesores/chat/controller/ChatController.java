package com.workers.profesores.chat.controller;

import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return ResponseEntity.badRequest().body("Request vacío");
        }
        String result = chatService.runChat(request.getMessages());
        return ResponseEntity.ok(result != null ? result : "");
    }
}
