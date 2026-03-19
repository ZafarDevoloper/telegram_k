package com.example.demo.controller;

import com.example.demo.entity.ChatMessage;
import com.example.demo.repository.ChatMessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ChatController — Suxbat tarixi endpointlari
 */
@RestController
@RequestMapping("/api/admin/chats")
public class ChatController {

    private final ChatMessageRepository chatRepo;

    public ChatController(ChatMessageRepository chatRepo) {
        this.chatRepo = chatRepo;
    }

    // ─── Murojaat bo'yicha chat tarixi ────────────────────────────────────
    @GetMapping("/{appId}")
    public ResponseEntity<List<ChatMessage>> getByApp(@PathVariable Long appId) {
        return ResponseEntity.ok(chatRepo.findByAppIdOrderBySentAtAsc(appId));
    }

    // ─── Chat xabarlari soni ──────────────────────────────────────────────
    @GetMapping("/{appId}/count")
    public ResponseEntity<Map<String, Long>> count(@PathVariable Long appId) {
        return ResponseEntity.ok(Map.of("count", chatRepo.countByAppId(appId)));
    }

    // ─── Chat tarixi bo'lgan murojaatlar ro'yxati ─────────────────────────
    @GetMapping("/active-apps")
    public ResponseEntity<List<Long>> getActiveApps() {
        return ResponseEntity.ok(chatRepo.findAppIdsWithChat());
    }

    // ─── Murojaat chatini o'chirish ───────────────────────────────────────
    @DeleteMapping("/{appId}")
    public ResponseEntity<Void> deleteChat(@PathVariable Long appId) {
        chatRepo.deleteByAppId(appId);
        return ResponseEntity.noContent().build();
    }
}