package com.example.demo.controller;

import com.example.demo.entity.Application;
import com.example.demo.entity.ChatMessage;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.service.ApplicationService;
import com.example.demo.service.ExcelExportService;
import com.example.demo.service.ExportService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ApplicationController — (O4 fix) Excel export endpoint qo'shildi.
 *
 * MUAMMO: admin.html da "📊 Excel" tugmasi bor, lekin
 * /api/admin/applications/export/excel endpoint mavjud emas — 404.
 *
 * YECHIM: ExcelExportService inject qilib ikkita endpoint qo'shildi:
 *   GET /api/admin/applications/export/excel         — barcha
 *   GET /api/admin/applications/{id}/export/excel    — bitta
 */
@RestController
@RequestMapping("/api/admin/applications")
public class ApplicationController {

    private final ApplicationService     appService;
    private final ExportService          exportService;
    private final ExcelExportService     excelExportService; // [O4] yangi
    private final ChatMessageRepository  chatMsgRepo;

    public ApplicationController(ApplicationService appService,
                                 ExportService exportService,
                                 ExcelExportService excelExportService,
                                 ChatMessageRepository chatMsgRepo) {
        this.appService         = appService;
        this.exportService      = exportService;
        this.excelExportService = excelExportService;
        this.chatMsgRepo        = chatMsgRepo;
    }

    // ─── 1. Ro'yxat (filter + pagination) ────────────────────────────────
    @GetMapping
    public ResponseEntity<Page<Application>> getAll(
            @RequestParam(required = false)                    ApplicationStatus status,
            @RequestParam(required = false)                    String lang,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(defaultValue = "0")                  int page,
            @RequestParam(defaultValue = "20")                 int size
    ) {
        return ResponseEntity.ok(appService.getApplications(status, lang, search, page, size));
    }

    // ─── 2. Bitta murojaat ────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Application> getOne(@PathVariable Long id) {
        appService.markAsViewed(id);
        return ResponseEntity.ok(appService.getById(id));
    }

    // ─── 3. Javob berish ──────────────────────────────────────────────────
    @PostMapping("/{id}/reply")
    public ResponseEntity<Application> reply(
            @PathVariable Long id,
            @RequestBody  Map<String, String> body
    ) {
        String replyText = body.get("reply");
        if (replyText == null || replyText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(appService.replyToApplication(id, replyText));
    }

    // ─── 4. Status o'zgartirish ───────────────────────────────────────────
    @PatchMapping("/{id}/status")
    public ResponseEntity<Application> updateStatus(
            @PathVariable Long id,
            @RequestBody  Map<String, String> body
    ) {
        try {
            ApplicationStatus newStatus = ApplicationStatus.valueOf(body.get("status"));
            return ResponseEntity.ok(appService.updateStatus(id, newStatus));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ─── 5. Ko'rildi deb belgilash ────────────────────────────────────────
    @PostMapping("/{id}/viewed")
    public ResponseEntity<Void> markViewed(@PathVariable Long id) {
        appService.markAsViewed(id);
        return ResponseEntity.ok().build();
    }

    // ─── 6. O'chirish ─────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        appService.deleteApplication(id);
        return ResponseEntity.noContent().build();
    }

    // ─── 7. Statistika ────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(appService.getStats());
    }

    // ─── 8. Word export (barcha) ──────────────────────────────────────────
    @GetMapping("/export/word")
    public ResponseEntity<byte[]> exportAllWord() throws Exception {
        byte[] data = exportService.exportToWord();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"murojaatlar.docx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(data);
    }

    // ─── 9. Word export (bitta) ───────────────────────────────────────────
    @GetMapping("/{id}/export/word")
    public ResponseEntity<byte[]> exportOneWord(@PathVariable Long id) throws Exception {
        byte[] data = exportService.exportSingleToWord(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"murojaat_" + id + ".docx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(data);
    }

    // ─── [O4] 10. Excel export (barcha) ──────────────────────────────────
    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportAllExcel() throws Exception {
        byte[] data = excelExportService.exportToExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"murojaatlar.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    // ─── [O4] 11. Excel export (bitta) ───────────────────────────────────
    @GetMapping("/{id}/export/excel")
    public ResponseEntity<byte[]> exportOneExcel(@PathVariable Long id) throws Exception {
        byte[] data = excelExportService.exportSingleToExcel(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"murojaat_" + id + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    // ─── 12. Chat tarixi ──────────────────────────────────────────────────
    @GetMapping("/{id}/chat")
    public ResponseEntity<List<ChatMessage>> getChatHistory(@PathVariable Long id) {
        return ResponseEntity.ok(chatMsgRepo.findByAppIdOrderBySentAtAsc(id));
    }
}