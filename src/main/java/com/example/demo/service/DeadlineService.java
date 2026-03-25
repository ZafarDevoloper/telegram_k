package com.example.demo.service;

import com.example.demo.entity.Application;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.enums.Priority;
import com.example.demo.repository.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * DeadlineService — Murojaat bajarish muddatini boshqaradi.
 *
 * Qoidalar:
 *   NORMAL murojaat  → 10 kun
 *   URGENT murojaat  → 5 kun
 *
 * Deadline o'tib ketganda:
 *   1. Adminlarga Telegram xabari yuboriladi
 *   2. Murojaat holati OVERDUE ga o'tadi (IN_REVIEW bo'lib qolsa)
 *   3. Foydalanuvchiga xabar yuboriladi
 *
 * Tekshirish: har 1 soatda (@Scheduled)
 */
@Service
public class DeadlineService {

    private static final Logger log = LoggerFactory.getLogger(DeadlineService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Oddiy murojaat uchun muddat (kun) */
    public static final int NORMAL_DEADLINE_DAYS = 10;
    /** Shoshilinch murojaat uchun muddat (kun) */
    public static final int URGENT_DEADLINE_DAYS = 5;

    private final ApplicationRepository repository;

    @Value("${telegram.admin.chat.ids}")
    private String adminChatIdsRaw;

    private AbsSender sender;

    public DeadlineService(ApplicationRepository repository) {
        this.repository = repository;
    }

    public void setSender(AbsSender sender) {
        this.sender = sender;
    }

    // ─── Deadline hisoblash ───────────────────────────────────────────────

    /**
     * Murojaat saqlanganda yoki priority o'zgarganda chaqiriladi.
     * Deadline = submissionTime + (URGENT ? 5 : 10) kun
     */
    public LocalDateTime calculateDeadline(Application app) {
        LocalDateTime base = app.getSubmissionTime() != null
                ? app.getSubmissionTime()
                : LocalDateTime.now();
        int days = (app.getPriority() == Priority.URGENT)
                ? URGENT_DEADLINE_DAYS
                : NORMAL_DEADLINE_DAYS;
        return base.plusDays(days);
    }

    /**
     * Murojaatga deadline belgilaydi va saqlaydi.
     * ApplicationService.replyToApplication() va setPriority() dan chaqiriladi.
     */
    public void assignDeadline(Application app) {
        if (app.getDeadline() == null) {
            app.setDeadline(calculateDeadline(app));
            repository.save(app);
            log.debug("Deadline belgilandi: murojaat #{} → {}", app.getId(),
                    app.getDeadline().format(FMT));
        }
    }

    /**
     * Priority o'zgarganda deadline qayta hisoblanadi.
     * Agar eski deadline hali o'tmagan bo'lsa ham yangilanadi.
     */
    public void reassignDeadline(Application app) {
        app.setDeadline(calculateDeadline(app));
        app.setDeadlineNotified(false);
        repository.save(app);
        log.info("Deadline yangilandi: murojaat #{} → {}", app.getId(),
                app.getDeadline().format(FMT));
    }

    // ─── Deadline tekshirish (har 1 soatda) ──────────────────────────────

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void checkDeadlines() {
        if (sender == null) return;

        try {
            LocalDateTime now = LocalDateTime.now();

            // Deadline o'tib ketgan, hali yopilmagan murojaatlar
            List<Application> overdue = repository.findOverdueApplications(now);

            if (overdue.isEmpty()) return;

            log.info("Deadline o'tib ketgan murojaatlar: {} ta", overdue.size());
            List<String> admins = parseAdminIds();

            for (Application app : overdue) {
                try {
                    String deadlineStr = app.getDeadline().format(FMT);

                    // 1. Adminlarga xabar
                    String adminMsg = buildAdminOverdueMessage(app, deadlineStr);
                    for (String adminId : admins) {
                        sendSafe(adminId, adminMsg);
                    }

                    // 2. Foydalanuvchiga xabar
                    if (app.getChatId() != null) {
                        String lang = app.getLang() != null ? app.getLang() : "uz";
                        String userMsg = buildUserOverdueMessage(lang, app.getId(), deadlineStr);
                        sendSafe(app.getChatId(), userMsg);
                    }

                    // 3. Deadline xabari yuborildi deb belgilash
                    app.setDeadlineNotified(true);
                    repository.save(app);

                    log.info("Deadline xabari yuborildi: murojaat #{}", app.getId());

                } catch (Exception e) {
                    log.error("Deadline xabari xatosi [appId={}]: {}", app.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("checkDeadlines xatosi: {}", e.getMessage());
        }
    }

    // ─── Xabar matnlari ───────────────────────────────────────────────────

    private String buildAdminOverdueMessage(Application app, String deadlineStr) {
        String priority = (app.getPriority() == Priority.URGENT) ? "SHOSHILINCH" : "Oddiy";
        String desc = app.getDescription() != null
                ? (app.getDescription().length() > 100
                ? app.getDescription().substring(0, 100) + "..."
                : app.getDescription())
                : "—";

        return """
                ⏰ DEADLINE O'TIB KETDI!
                
                Murojaat: #%d
                Ustuvorlik: %s
                Muddat: %s
                Holat: %s
                Yuboruvchi: %s
                Tel: %s
                
                Mazmun: %s
                
                Zudlik bilan javob bering!
                """.formatted(
                app.getId(),
                priority,
                deadlineStr,
                app.getStatus().name(),
                app.getApplicantName() != null ? app.getApplicantName() : "—",
                app.getPhoneNumber() != null ? app.getPhoneNumber() : "—",
                desc
        );
    }

    private String buildUserOverdueMessage(String lang, Long appId, String deadlineStr) {
        return switch (lang) {
            case "ru" -> "⚠️ Срок исполнения обращения №" + appId +
                    " (" + deadlineStr + ") истёк. Скоро ответим.";
            case "en" -> "⚠️ The deadline (" + deadlineStr + ") for request #" + appId +
                    " has passed. We'll respond soon.";
            default   -> "⚠️ #" + appId + " raqamli murojaatingiz muddati (" + deadlineStr +
                    ") o'tib ketdi. Tez orada javob beriladi.";
        };
    }

    // ─── Yordamchi metodlar ───────────────────────────────────────────────

    private void sendSafe(String chatId, String text) {
        try {
            if (sender != null && chatId != null && !chatId.isBlank()) {
                sender.execute(new SendMessage(chatId, text));
            }
        } catch (TelegramApiException e) {
            log.error("Xabar yuborishda xato [chatId={}]: {}", chatId, e.getMessage());
        }
    }

    private List<String> parseAdminIds() {
        if (adminChatIdsRaw == null || adminChatIdsRaw.isBlank()) return List.of();
        return Arrays.stream(adminChatIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String formatDeadline(Application app) {
        if (app.getDeadline() == null) return "—";
        return app.getDeadline().format(FMT);
    }
}