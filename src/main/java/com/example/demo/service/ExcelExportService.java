package com.example.demo.service;

import com.example.demo.entity.Application;
import com.example.demo.enums.Priority;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.ApplicationRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
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
 * ExcelExportService — Murojaatlarni Excel (.xlsx) formatiga export.
 *
 * O'zgarishlar:
 *   - exportToExcel() da findAll() → pagination (OutOfMemoryError oldini olish)
 *   - exportSingleToExcel() da RuntimeException → ResourceNotFoundException (404)
 *   - SLF4J logger qo'shildi
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int EXPORT_PAGE_SIZE = 200;

    private final ApplicationRepository repository;

    public ExcelExportService(ApplicationRepository repository) {
        this.repository = repository;
    }

    // ─── Barcha murojaatlar (pagination bilan) ────────────────────────────
    public byte[] exportToExcel() throws Exception {
        long total = repository.count();
        log.info("Excel export boshlandi: {} ta murojaat", total);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("Murojaatlar");
            setupColumns(sheet);

            // Sarlavha + header
            addTitleRow(wb, sheet, "Barcha Murojaatlar", total);
            addHeaderRow(wb, sheet);

            // Stillar — bir marta yaratiladi
            StyleSet styles = new StyleSet(wb);

            int rowNum = 2;
            int pageNum = 0;
            Page<Application> pageData;
            do {
                pageData = repository.findAll(
                        PageRequest.of(pageNum++, EXPORT_PAGE_SIZE,
                                Sort.by("submissionTime").descending()));
                for (Application app : pageData.getContent()) {
                    writeRow(sheet, rowNum++, app, styles);
                }
            } while (pageData.hasNext());

            sheet.createFreezePane(0, 2);
            sheet.setAutoFilter(new CellRangeAddress(1, 1, 0, 9));

            wb.write(out);
            log.info("Excel export tugadi: {} ta murojaat", total);
            return out.toByteArray();
        }
    }

    // ─── Bitta murojaat ───────────────────────────────────────────────────
    public byte[] exportSingleToExcel(Long id) throws Exception {
        Application app = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", id));
        log.info("Excel export: murojaat #{}", id);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("Murojaat");
            setupColumns(sheet);
            addTitleRow(wb, sheet, "Murojaat #" + id, 1);
            addHeaderRow(wb, sheet);
            writeRow(sheet, 2, app, new StyleSet(wb));
            sheet.createFreezePane(0, 2);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ─── Sheet sozlamalari ────────────────────────────────────────────────
    private void setupColumns(XSSFSheet sheet) {
        sheet.setColumnWidth(0, 8  * 256);
        sheet.setColumnWidth(1, 30 * 256);
        sheet.setColumnWidth(2, 15 * 256);
        sheet.setColumnWidth(3, 14 * 256);
        sheet.setColumnWidth(4, 8  * 256);
        sheet.setColumnWidth(5, 18 * 256);
        sheet.setColumnWidth(6, 18 * 256);
        sheet.setColumnWidth(7, 18 * 256);
        sheet.setColumnWidth(8, 40 * 256);
        sheet.setColumnWidth(9, 12 * 256);
    }

    private void addTitleRow(XSSFWorkbook wb, XSSFSheet sheet, String title, long count) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        Cell c = row.createCell(0);
        c.setCellValue(title + "  |  Jami: " + count + " ta");
        c.setCellStyle(titleStyle(wb));
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 9));
    }

    private void addHeaderRow(XSSFWorkbook wb, XSSFSheet sheet) {
        String[] headers = {"#ID","Murojaat matni","Holat","Ustuvorlik",
                "Til","Yuborilgan","Ko'rilgan","Javob vaqti","Admin javobi","Media"};
        Row hRow = sheet.createRow(1);
        hRow.setHeightInPoints(20);
        CellStyle hs = headerStyle(wb);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(hs);
        }
    }

    private void writeRow(XSSFSheet sheet, int rowNum, Application app, StyleSet s) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(18);

        boolean isUrgent  = Priority.URGENT == app.getPriority();
        boolean isReplied = app.getAdminReply() != null && !app.getAdminReply().isBlank();
        boolean isAlt     = rowNum % 2 == 0;

        CellStyle base = isUrgent
                ? (isAlt ? s.altUrgent : s.urgent)
                : isReplied ? s.replied
                : (isAlt ? s.alt : s.normal);

        Cell idCell = row.createCell(0);
        idCell.setCellValue(app.getId());
        idCell.setCellStyle(s.mono);

        setCell(row, 1, truncate(app.getDescription(), 200), base);
        setCell(row, 2, statusText(app), base);
        setCell(row, 3, isUrgent ? "Shoshilinch" : "Oddiy", base);
        setCell(row, 4, app.getLang() != null ? app.getLang().toUpperCase() : "—", base);
        setCell(row, 5, app.getSubmissionTime() != null ? app.getSubmissionTime().format(FMT) : "—", base);
        setCell(row, 6, app.getViewedAt()       != null ? app.getViewedAt().format(FMT)       : "—", base);
        setCell(row, 7, app.getRepliedAt()      != null ? app.getRepliedAt().format(FMT)      : "—", base);
        setCell(row, 8, isReplied ? app.getAdminReply() : "—", base);
        setCell(row, 9, mediaLabel(app.getFileType()), base);
    }

    // ─── Stillar to'plami — bir marta yaratiladi ──────────────────────────
    private static class StyleSet {
        final CellStyle normal, alt, urgent, altUrgent, replied, mono;
        StyleSet(XSSFWorkbook wb) {
            normal   = plainStyle(wb, null, null, false);
            alt      = plainStyle(wb, new byte[]{(byte)242,(byte)245,(byte)255}, null, false);
            urgent   = plainStyle(wb, new byte[]{(byte)255,(byte)235,(byte)235},
                    new byte[]{(byte)180,0,0}, true);
            altUrgent= plainStyle(wb, new byte[]{(byte)255,(byte)220,(byte)220},
                    new byte[]{(byte)180,0,0}, true);
            replied  = plainStyle(wb, new byte[]{(byte)235,(byte)255,(byte)235},
                    new byte[]{0,(byte)100,0}, false);
            mono     = monoStyle(wb);
        }

        private static CellStyle plainStyle(XSSFWorkbook wb, byte[] bgRgb, byte[] fgRgb, boolean bold) {
            XSSFCellStyle s = wb.createCellStyle();
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) 10);
            if (bold) f.setBold(true);
            if (fgRgb != null) f.setColor(new XSSFColor(fgRgb, null));
            s.setFont(f);
            if (bgRgb != null) {
                s.setFillForegroundColor(new XSSFColor(bgRgb, null));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setBorderTop(BorderStyle.THIN);    s.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderBottom(BorderStyle.THIN); s.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderLeft(BorderStyle.THIN);   s.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderRight(BorderStyle.THIN);  s.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            return s;
        }

        private static CellStyle monoStyle(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            XSSFFont f = wb.createFont();
            f.setFontName("Courier New");
            f.setFontHeightInPoints((short) 10);
            f.setColor(new XSSFColor(new byte[]{(byte)80,(byte)80,(byte)140}, null));
            s.setFont(f);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setBorderTop(BorderStyle.THIN);    s.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderBottom(BorderStyle.THIN); s.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderLeft(BorderStyle.THIN);   s.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBorderRight(BorderStyle.THIN);  s.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            return s;
        }
    }

    // ─── Stil metodlari ───────────────────────────────────────────────────
    private CellStyle titleStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 14);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)30,(byte)40,(byte)80}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle headerStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)47,(byte)84,(byte)150}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderTop(BorderStyle.THIN);    s.setTopBorderColor(IndexedColors.WHITE.getIndex());
        s.setBorderBottom(BorderStyle.THIN); s.setBottomBorderColor(IndexedColors.WHITE.getIndex());
        s.setBorderLeft(BorderStyle.THIN);   s.setLeftBorderColor(IndexedColors.WHITE.getIndex());
        s.setBorderRight(BorderStyle.THIN);  s.setRightBorderColor(IndexedColors.WHITE.getIndex());
        return s;
    }

    // ─── Yordamchi metodlar ───────────────────────────────────────────────
    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "—");
        c.setCellStyle(style);
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String statusText(Application app) {
        if (app.getStatus() == null) return "—";
        return switch (app.getStatus()) {
            case PENDING   -> "Kutmoqda";
            case IN_REVIEW -> "Ko'rilmoqda";
            case REPLIED   -> "Javob berildi";
            case CLOSED    -> "Yopildi";
        };
    }

    private String mediaLabel(String type) {
        if (type == null) return "—";
        return switch (type.toLowerCase()) {
            case "photo"      -> "Rasm";
            case "audio"      -> "Audio";
            case "voice"      -> "Ovoz";
            case "video"      -> "Video";
            case "video_note" -> "Video xabar";
            case "animation"  -> "GIF";
            case "document"   -> "Hujjat";
            case "sticker"    -> "Stiker";
            default           -> type;
        };
    }
}