package com.example.demo.service;

import com.example.demo.entity.Application;
import com.example.demo.enums.ApplicationStatus;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReminderService — Javobsiz murojaatlar uchun eslatmalar.
 *
 * O'zgarishlar:
 *   - System.out/err → SLF4J logger
 *   - Mantiq o'zgarmadi
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int TELEGRAM_MAX_LENGTH = 4000;

    private final ApplicationRepository repository;

    @Value("${telegram.admin.chat.ids}")
    private String adminChatIdsRaw;

    private AbsSender sender;

    private final Map<Long, ReminderEntry> reminderRegistry = new ConcurrentHashMap<>();

    public ReminderService(ApplicationRepository repository) {
        this.repository = repository;
    }

    public void setSender(AbsSender sender) {
        this.sender = sender;
    }

    // ─── Reminder qo'shish (entity bilan) ────────────────────────────────
    public void scheduleReminder(Application app) {
        boolean isUrgent = app.getPriority() != null
                && "URGENT".equals(app.getPriority().name());
        long hoursUntilFirst = isUrgent ? 1L : 24L;
        reminderRegistry.put(app.getId(),
                new ReminderEntry(LocalDateTime.now().plusHours(hoursUntilFirst), 0));
        log.debug("Reminder qo'shildi: murojaat #{}, {}h", app.getId(), hoursUntilFirst);
    }

    public void scheduleReminder(Long appId) {
        repository.findById(appId).ifPresent(this::scheduleReminder);
    }

    public void cancelReminder(Long appId) {
        reminderRegistry.remove(appId);
    }

    // ─── Reminder tekshirish (har 15 daqiqada) ────────────────────────────
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void checkPendingReminders() {
        if (reminderRegistry.isEmpty() || sender == null) return;

        LocalDateTime now    = LocalDateTime.now();
        List<String>  admins = parseAdminIds();
        List<Long>    appIds = new ArrayList<>(reminderRegistry.keySet());

        for (Long appId : appIds) {
            ReminderEntry entry = reminderRegistry.get(appId);
            if (entry == null || now.isBefore(entry.remindAt)) continue;

            try {
                repository.findById(appId).ifPresentOrElse(
                        app -> {
                            if (isUnresolved(app)) {
                                sendReminderToAdmins(app, entry.count + 1, admins);
                                if (entry.count < 2) {
                                    reminderRegistry.put(appId,
                                            new ReminderEntry(LocalDateTime.now().plusHours(24), entry.count + 1));
                                } else {
                                    reminderRegistry.remove(appId);
                                    log.info("Reminder tugadi: murojaat #{}", appId);
                                }
                            } else {
                                reminderRegistry.remove(appId);
                            }
                        },
                        () -> reminderRegistry.remove(appId)
                );
            } catch (Exception e) {
                log.error("Reminder xatosi [appId={}]: {}", appId, e.getMessage());
            }
        }
    }

    // ─── Startup da tiklash ───────────────────────────────────────────────
    @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
    public void restoreRemindersOnStartup() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusHours(24);
            repository.findUnrepliedBefore(threshold).forEach(app -> {
                if (!reminderRegistry.containsKey(app.getId())) {
                    reminderRegistry.put(app.getId(),
                            new ReminderEntry(LocalDateTime.now().plusMinutes(5), 0));
                }
            });
            log.info("Startup: {} ta reminder tiklandi.", reminderRegistry.size());
        } catch (Exception e) {
            log.error("Startup restore xato: {}", e.getMessage());
        }
    }

    // ─── Uzun matnni bo'lib yuborish ──────────────────────────────────────
    public void splitAndSend(String chatId, String text) {
        if (text == null || text.isBlank()) return;
        if (text.length() <= TELEGRAM_MAX_LENGTH) {
            sendSafe(chatId, text);
            return;
        }

        int start    = 0;
        int partNum  = 1;
        int total    = (int) Math.ceil((double) text.length() / TELEGRAM_MAX_LENGTH);

        while (start < text.length()) {
            int end = Math.min(start + TELEGRAM_MAX_LENGTH, text.length());
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                int lastSpace   = text.lastIndexOf(' ',  end);
                int breakAt     = Math.max(lastNewline, lastSpace);
                if (breakAt > start) end = breakAt;
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                String prefix = total > 1 ? "(" + partNum + "/" + total + ")\n" : "";
                sendSafe(chatId, prefix + part);
                partNum++;
            }
            start = end;
        }
    }

    private boolean isUnresolved(Application app) {
        return app.getStatus() == ApplicationStatus.PENDING
                || app.getStatus() == ApplicationStatus.IN_REVIEW;
    }

    private void sendReminderToAdmins(Application app, int num, List<String> admins) {
        String desc = app.getDescription() != null
                ? (app.getDescription().length() > 120
                ? app.getDescription().substring(0, 120) + "..."
                : app.getDescription())
                : "—";

        boolean isUrgent  = app.getPriority() != null && "URGENT".equals(app.getPriority().name());
        String urgentMark = isUrgent ? "SHOSHILINCH  " : "";

        String text = """
            ESLATMA #%d — Javobsiz murojaat!

            %sMurojaat #%d
            Kelgan vaqt: %s
            Holat:       %s
            Til:         %s

            Mazmun:
            %s

            Javob bering yoki holatini yangilang.
            """.formatted(
                num, urgentMark, app.getId(),
                app.getSubmissionTime() != null ? app.getSubmissionTime().format(FMT) : "—",
                app.getStatus(),
                app.getLang() != null ? app.getLang().toUpperCase() : "—",
                desc
        );

        for (String adminId : admins) splitAndSend(adminId, text);
    }

    private void sendSafe(String chatId, String text) {
        try {
            if (sender != null && text != null && !text.isBlank()) {
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

    public int getPendingReminderCount() { return reminderRegistry.size(); }

    private record ReminderEntry(LocalDateTime remindAt, int count) {}
}