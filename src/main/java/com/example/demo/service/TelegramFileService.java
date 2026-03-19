package com.example.demo.service;

import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.ApplicationRepository;
import com.example.demo.entity.Application;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * TelegramFileService — Telegram fayllarini yuklab saqlash.
 *
 * O'zgarishlar:
 *   - RuntimeException → ResourceNotFoundException (to'g'ri 404)
 *   - SLF4J logger
 *   - @PostConstruct — uploads papkasini startup da yaratadi
 *   - Path traversal himoyasi saqlanib qolindi
 */
@Service
public class TelegramFileService {

    private static final Logger log = LoggerFactory.getLogger(TelegramFileService.class);

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    private final ApplicationRepository repository;
    private final RestTemplate           restTemplate = new RestTemplate();

    public TelegramFileService(ApplicationRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void initUploadDir() {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("Upload papkasi yaratildi: {}", uploadPath.toAbsolutePath());
            }
            if (!Files.isWritable(uploadPath)) {
                log.warn("Upload papkasiga yozish huquqi yo'q: {}", uploadPath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Upload papkasini yaratishda xato: {}", e.getMessage());
        }
    }

    // ─── Murojaat faylini yuklab saqlash ──────────────────────────────────
    public FileDownloadResult downloadAndSave(Long appId) throws Exception {
        Application app = repository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", appId));

        if (app.getFileId() == null || app.getFileId().isBlank()) {
            throw new IllegalArgumentException("#" + appId + " murojaatda fayl mavjud emas");
        }

        return downloadByFileId(app.getFileId(), app.getFileType(), appId);
    }

    // ─── fileId bo'yicha yuklab saqlash ───────────────────────────────────
    public FileDownloadResult downloadByFileId(String fileId,
                                               String fileType,
                                               Long appId) throws Exception {
        String getFileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(getFileUrl, Map.class);

        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            throw new RuntimeException("Telegram fayl ma'lumotini olishda xatolik");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fileInfo = (Map<String, Object>) response.get("result");
        String filePath  = (String) fileInfo.get("file_path");
        Object sizeObj   = fileInfo.get("file_size");
        long   fileSize  = sizeObj != null ? ((Number) sizeObj).longValue() : 0;

        String fileName    = sanitizeFileName(buildFileName(filePath, fileType, appId));
        String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
        Path   savePath    = buildSavePath(appId, fileName);

        validatePathBoundary(savePath);
        Files.createDirectories(savePath.getParent());

        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, savePath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Fayl yuklandi: {} ({} bayt)", fileName, fileSize);
        return new FileDownloadResult(fileName, savePath.toString(), fileSize, fileType, filePath);
    }

    // ─── Saqlangan faylni o'qish ──────────────────────────────────────────
    public byte[] readFile(Long appId) throws IOException {
        repository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", appId));

        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        String pattern  = appId + "_*";

        try (var dirs = Files.walk(uploadRoot, 2)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
                    for (Path p : stream) {
                        validatePathBoundary(p);
                        return Files.readAllBytes(p);
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            throw new RuntimeException("Fayl qidirishda xatolik: " + e.getMessage());
        }

        throw new ResourceNotFoundException("Fayl serverda topilmadi. Avval yuklab olish kerak.");
    }

    // ─── Xavfsizlik metodlari ─────────────────────────────────────────────
    private void validatePathBoundary(Path path) {
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(uploadRoot)) {
            throw new SecurityException(
                    "Fayl yo'li ruxsat etilgan papkadan tashqarida: " + path);
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "file_unknown";
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_").replace("..", "_");
        return sanitized.length() > 200 ? sanitized.substring(0, 200) : sanitized;
    }

    private String buildFileName(String filePath, String fileType, Long appId) {
        String originalName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        if (!originalName.contains(".")) originalName += getExtension(fileType);
        return appId + "_" + originalName;
    }

    private String getExtension(String fileType) {
        if (fileType == null) return ".bin";
        return switch (fileType.toLowerCase()) {
            case "photo"                -> ".jpg";
            case "audio"                -> ".mp3";
            case "voice"                -> ".ogg";
            case "video", "video_note"  -> ".mp4";
            case "animation"            -> ".gif";
            case "sticker"              -> ".webp";
            default                     -> ".bin";
        };
    }

    private Path buildSavePath(Long appId, String fileName) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return Paths.get(uploadDir, datePath, fileName);
    }

    // ─── Natija DTO ───────────────────────────────────────────────────────
    public record FileDownloadResult(
            String fileName, String localPath, long fileSize, String fileType, String telegramPath
    ) {
        public String getFileSizeStr() {
            if (fileSize <= 0)          return "—";
            if (fileSize < 1024)        return fileSize + " B";
            if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
            return String.format("%.1f MB", fileSize / (1024.0 * 1024));
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "fileName",     fileName,
                    "localPath",    localPath,
                    "fileSize",     getFileSizeStr(),
                    "fileType",     fileType != null ? fileType : "",
                    "telegramPath", telegramPath != null ? telegramPath : ""
            );
        }
    }
}