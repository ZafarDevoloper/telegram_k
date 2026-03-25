package com.example.demo.controller;

import com.example.demo.entity.Application;
import com.example.demo.entity.ChatMessage;
import com.example.demo.enums.ApplicationSection;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ApplicationController — Murojaat boshqaruvi.
 *
 * Yangi endpointlar:
 *   PATCH /api/admin/applications/{id}/section   — Bo'lim belgilash
 *   PATCH /api/admin/applications/{id}/deadline  — Muddat belgilash (kun soni bilan)
 *
 * Bo'lim → muddat qoidasi:
 *   NORMAL  → +10 kun
 *   URGENT  → +5  kun
 *   Ixtiyoriy: admin o'zi kun sonini kiritishi mumkin
 */
@RestController
@RequestMapping("/api/admin/applications")
public class ApplicationController {

    private final AdminReplyFileService adminReplyFileService;
    private static final int DEADLINE_NORMAL_DAYS = 10;
    private static final int DEADLINE_URGENT_DAYS = 5;

    private final ApplicationService     appService;
    private final ExportService          exportService;
    private final ExcelExportService     excelExportService;
    private final ChatMessageRepository  chatMsgRepo;

    public ApplicationController(ApplicationService appService,
                                 ExportService exportService,
                                 ExcelExportService excelExportService,
                                 ChatMessageRepository chatMsgRepo,AdminReplyFileService adminReplyFileService) {
        this.appService         = appService;
        this.exportService      = exportService;
        this.excelExportService = excelExportService;
        this.chatMsgRepo        = chatMsgRepo;
        this.adminReplyFileService = adminReplyFileService;
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

    // ─── 10. Excel export (barcha) ────────────────────────────────────────
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

    // ─── 11. Excel export (bitta) ─────────────────────────────────────────
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

    // ═══════════════════════════════════════════════════════════════════════
    //  [YANGI] BO'LIM & MUDDAT ENDPOINTLARI
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Bo'lim belgilash.
     * NORMAL  → muddat avtomatik +10 kun
     * URGENT  → muddat avtomatik +5  kun
     *
     * So'rov:  { "section": "NORMAL" | "URGENT" }
     * Javob:   { "message": "...", "section": "...", "deadline": "...", "days": N }
     */
    @PatchMapping("/{id}/section")
    public ResponseEntity<?> updateSection(
            @PathVariable Long id,
            @RequestBody  Map<String, String> body
    ) {
        String sectionStr = body.get("section");
        if (sectionStr == null || sectionStr.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "'section' maydoni kerak: NORMAL yoki URGENT"));
        }

        ApplicationSection section;
        try {
            section = ApplicationSection.valueOf(sectionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Noto'g'ri bo'lim: " + sectionStr));
        }

        // Avtomatik muddat
        int days = (section == ApplicationSection.URGENT)
                ? DEADLINE_URGENT_DAYS
                : DEADLINE_NORMAL_DAYS;

        Application app = appService.assignSection(id, section, days);

        return ResponseEntity.ok(Map.of(
                "message",  "Bo'lim belgilandi: " + section.name(),
                "section",  app.getSection().name(),
                "deadline", app.getDeadline().toString(),
                "days",     days
        ));
    }

    /**
     * Muddatni ixtiyoriy o'zgartirish (admin o'zi kun sonini kiritadi).
     *
     * So'rov:  { "days": 7 }
     * Javob:   { "message": "...", "deadline": "...", "days": 7 }
     */
    @PatchMapping("/{id}/deadline")
    public ResponseEntity<?> updateDeadline(
            @PathVariable Long id,
            @RequestBody  Map<String, Integer> body
    ) {
        Integer days = body.get("days");
        if (days == null || days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Kun soni 1 dan 365 gacha bo'lishi kerak"));
        }

        Application app = appService.setDeadline(id, days);

        return ResponseEntity.ok(Map.of(
                "message",  "Muddat belgilandi",
                "deadline", app.getDeadline().toString(),
                "days",     days
        ));
    }

    @PostMapping(value = "/{id}/reply-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> replyWithFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false, defaultValue = "") String caption) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Fayl bo'sh bo'lishi mumkin emas"));
        }

        try {
            // Yangi xizmatdan foydalanamiz (eng to'g'ri yondashuv)
            Map<String, Object> result = adminReplyFileService.sendReplyWithFile(id, caption, file);
            return ResponseEntity.ok(result);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("replyWithFile xatosi", e);
            return ResponseEntity.status(500).body(Map.of("error", "Fayl yuborishda xatolik: " + e.getMessage()));
        }
    }
}