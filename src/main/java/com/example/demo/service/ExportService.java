package com.example.demo.service;

import com.example.demo.entity.Application;
import com.example.demo.enums.Priority;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.ApplicationRepository;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ExportService — Murojaatlarni Word (.docx) formatiga export qilish.
 *
 * O'zgarishlar:
 *   - exportToWord() da findAll() o'rniga pagination — OutOfMemoryError oldini olish
 *   - exportSingleToWord() da ResourceNotFoundException (to'g'ri 404)
 *   - SLF4J logger
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int EXPORT_PAGE_SIZE = 100;

    private final ApplicationRepository repository;

    public ExportService(ApplicationRepository repository) {
        this.repository = repository;
    }

    // ─── Barcha murojaatlarni export (pagination bilan) ───────────────────
    /**
     * findAll() o'rniga pagination — 100 ta kesilgan yuklash.
     * 10k+ murojaatda OutOfMemoryError bo'lmaydi.
     */
    public byte[] exportToWord() throws Exception {
        long total = repository.count();
        log.info("Word export boshlandi: {} ta murojaat", total);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            addTitle(doc, "Barcha Murojaatlar", total);

            int pageNum = 0;
            Page<Application> pageData;
            do {
                pageData = repository.findAll(
                        PageRequest.of(pageNum++, EXPORT_PAGE_SIZE,
                                Sort.by("submissionTime").descending()));
                for (Application app : pageData.getContent()) {
                    addDivider(doc);
                    addApplicationBlock(doc, app);
                }
            } while (pageData.hasNext());

            doc.write(out);
            log.info("Word export tugadi: {} ta murojaat", total);
            return out.toByteArray();
        }
    }

    // ─── Bitta murojaat export ────────────────────────────────────────────
    public byte[] exportSingleToWord(Long appId) throws Exception {
        Application app = repository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", appId));
        log.info("Word export: murojaat #{}", appId);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addTitle(doc, "Murojaat #" + appId, 1);
            addDivider(doc);
            addApplicationBlock(doc, app);
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ─── Sarlavha ─────────────────────────────────────────────────────────
    private void addTitle(XWPFDocument doc, String title, long count) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(title);
        r.setBold(true);
        r.setFontSize(16);
        r.setFontFamily("Arial");
        r.addBreak();

        XWPFParagraph p2 = doc.createParagraph();
        p2.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r2 = p2.createRun();
        r2.setText("Jami: " + count + " ta murojaat");
        r2.setFontSize(11);
        r2.setColor("777777");
        r2.setFontFamily("Arial");
        r2.addBreak();
    }

    // ─── Murojaat bloki ───────────────────────────────────────────────────
    private void addApplicationBlock(XWPFDocument doc, Application app) {
        XWPFParagraph headerPara = doc.createParagraph();
        XWPFRun headerRun = headerPara.createRun();
        String priorityTag = (app.getPriority() == Priority.URGENT) ? "SHOSHILINCH  " : "Oddiy  ";
        headerRun.setText(priorityTag + "Murojaat #" + app.getId());
        headerRun.setBold(true);
        headerRun.setFontSize(13);
        headerRun.setFontFamily("Arial");
        if (app.getPriority() == Priority.URGENT) headerRun.setColor("CC0000");

        XWPFTable table = doc.createTable(5, 2);
        table.setWidth("100%");
        styleTableBorder(table);

        setCell(table, 0, 0, "Holat",       true);
        setCell(table, 0, 1, getStatusText(app), false);
        setCell(table, 1, 0, "Til",         true);
        setCell(table, 1, 1, app.getLang() != null ? app.getLang().toUpperCase() : "—", false);
        setCell(table, 2, 0, "Yuborilgan",  true);
        setCell(table, 2, 1, app.getSubmissionTime() != null
                ? app.getSubmissionTime().format(FMT) : "—", false);
        setCell(table, 3, 0, "Ko'rilgan",   true);
        setCell(table, 3, 1, app.getViewedAt() != null
                ? app.getViewedAt().format(FMT) : "Ko'rilmagan", false);
        setCell(table, 4, 0, "Javob vaqti", true);
        setCell(table, 4, 1, app.getRepliedAt() != null
                ? app.getRepliedAt().format(FMT) : "—", false);

        addLabel(doc, "Murojaat matni:");
        addContent(doc, app.getDescription());

        if (app.getFileId() != null && !app.getFileId().isBlank()) {
            addLabel(doc, "Biriktirma:");
            addContent(doc, "Tur: " + app.getFileType() + "\nFile ID: " + app.getFileId());
        }

        addLabel(doc, "Admin javobi:");
        addContent(doc, (app.getAdminReply() != null && !app.getAdminReply().isBlank())
                ? app.getAdminReply() : "— Hali javob berilmagan —");

        doc.createParagraph();
    }

    // ─── Yordamchi metodlar ───────────────────────────────────────────────
    private void addLabel(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(120);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(11);
        r.setFontFamily("Arial");
        r.setColor("333333");
    }

    private void addContent(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text != null ? text : "—");
        r.setFontSize(11);
        r.setFontFamily("Arial");
    }

    private void addDivider(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setBorderBottom(Borders.SINGLE);
        p.setSpacingBefore(100);
        p.setSpacingAfter(100);
    }

    private void setCell(XWPFTable table, int row, int col, String text, boolean bold) {
        XWPFTableCell cell = table.getRow(row).getCell(col);
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(bold);
        r.setFontSize(10);
        r.setFontFamily("Arial");
        if (bold) r.setColor("444444");
    }

    private void styleTableBorder(XWPFTable table) {
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, "DDDDDD");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, "DDDDDD");
    }

    private String getStatusText(Application app) {
        if (app.getStatus() == null) return "—";
        return switch (app.getStatus()) {
            case PENDING   -> "Ko'rib chiqilmoqda";
            case IN_REVIEW -> "Jarayonda";
            case REPLIED   -> "Javob berildi";
            case CLOSED    -> "Yopildi";
        };
    }
}