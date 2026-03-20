package com.example.demo.controller;

import com.example.demo.service.TelegramFileService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * FileController — Telegram fayllarini yuklab saqlash va berish.
 *
 * Endpointlar:
 *   POST /api/admin/files/{appId}/download  — Telegram dan yuklab olish (serverga saqlaydi)
 *   GET  /api/admin/files/{appId}/view      — Serverdan brauzerda ko'rish (to'g'ri Content-Type bilan)
 *   GET  /api/admin/files/{appId}/save      — Serverdan yuklab olish (attachment)
 *   GET  /api/admin/files/{appId}/stream    — [YANGI] Telegram dan to'g'ridan-to'g'ri brauzerga
 *
 * TUZATILGAN MUAMMOLAR:
 *   1. /view endpoint APPLICATION_OCTET_STREAM qaytarardi → brauzer rasm/video ko'rsatmasdi.
 *      Endi fileType bo'yicha to'g'ri Content-Type aniqlanadi.
 *   2. /stream endpoint qo'shildi — server diskiga saqlamasdan to'g'ridan Telegram → brauzer.
 *      "Yuklab olish" uchun avval "Serverga saqlash" bosish shart emas.
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Fayl yuklab olishda xatolik: " + e.getMessage()));
        }
    }

    // ─── [YANGI] Telegram → brauzer (server diskiga saqlamasdan) ─────────
    /**
     * Avval "Serverga saqlash" bosmasdan to'g'ridan yuklab olish yoki ko'rish.
     * fileType bo'yicha Content-Type aniqlanadi: rasm, video, audio inline ochiladi.
     *
     * @param inline true = brauzerda ko'rish, false = yuklab olish (default)
     */
    @GetMapping("/{appId}/stream")
    public ResponseEntity<byte[]> streamFromTelegram(
            @PathVariable Long appId,
            @RequestParam(defaultValue = "false") boolean inline) {
        try {
            TelegramFileService.FileStreamResult result = fileService.streamFromTelegram(appId);
            String disposition = (inline ? "inline" : "attachment")
                    + "; filename=\"" + result.fileName() + "\"";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .contentType(result.mediaType())
                    .body(result.data());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─── Saqlangan faylni berish (yuklab olish) ──────────────────────────
    @GetMapping("/{appId}/save")
    public ResponseEntity<byte[]> serveFile(@PathVariable Long appId) {
        try {
            TelegramFileService.FileReadResult result = fileService.readFileWithMeta(appId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + result.fileName() + "\"")
                    .contentType(result.mediaType())
                    .body(result.data());
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ─── Brauzerda ko'rish (inline) — to'g'ri Content-Type bilan ────────
    /**
     * [TUZATILDI] Avval APPLICATION_OCTET_STREAM edi — brauzer rasm/videoni ko'rsatmasdi.
     * Endi fileType bo'yicha: image/jpeg, video/mp4, audio/ogg va h.k.
     */
    @GetMapping("/{appId}/view")
    public ResponseEntity<byte[]> viewFile(@PathVariable Long appId) {
        try {
            TelegramFileService.FileReadResult result = fileService.readFileWithMeta(appId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + result.fileName() + "\"")
                    .contentType(result.mediaType())
                    .body(result.data());
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}