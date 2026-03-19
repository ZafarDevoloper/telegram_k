package com.example.demo.controller;

import com.example.demo.service.TelegramFileService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * FileController — Telegram fayllarini yuklab saqlash va berish.
 *
 * Endpointlar:
 *   POST /api/admin/files/{appId}/download  — Telegram dan yuklab olish
 *   GET  /api/admin/files/{appId}/view      — Serverdan brauzerda ko'rish
 *   GET  /api/admin/files/{appId}/save      — Serverdan yuklab olish
 */
@RestController
@RequestMapping("/api/admin/files")
public class FileController {

    private final TelegramFileService fileService;

    public FileController(TelegramFileService fileService) {
        this.fileService = fileService;
    }

    // ─── Telegram dan serverga yuklab saqlash ────────────────────────────
    @PostMapping("/{appId}/download")
    public ResponseEntity<?> downloadFromTelegram(@PathVariable Long appId) {
        try {
            TelegramFileService.FileDownloadResult result =
                    fileService.downloadAndSave(appId);
            return ResponseEntity.ok(result.toMap());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Saqlangan faylni berish (yuklab olish) ──────────────────────────
    @GetMapping("/{appId}/save")
    public ResponseEntity<byte[]> serveFile(@PathVariable Long appId) {
        try {
            byte[] data = fileService.readFile(appId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"file_" + appId + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ─── Brauzerda ko'rish (inline) ──────────────────────────────────────
    @GetMapping("/{appId}/view")
    public ResponseEntity<byte[]> viewFile(@PathVariable Long appId) {
        try {
            byte[] data = fileService.readFile(appId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"file_" + appId + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}