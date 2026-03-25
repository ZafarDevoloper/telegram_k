package com.example.demo.entity;

import com.example.demo.enums.ApplicationCategory;
import com.example.demo.enums.ApplicationSection;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.enums.Priority;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
@Getter
@Setter
@Data
@Entity
@Table(name = "applications")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String applicantName;

    @Column(name = "deadline_notified")
    private boolean deadlineNotified = false;
    /** Foydalanuvchi telefon raqami */
    private String phoneNumber;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String lang;

    // Telegram chat ID — javob yuborish uchun
    private String chatId;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String adminReply;

    private LocalDateTime submissionTime = LocalDateTime.now();
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private ApplicationCategory category = ApplicationCategory.OTHER;

    // Qo'shimcha murojaat bo'lsa, asosiy murojaat ID
    private Long parentId;

    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;

    // ─── YANGI FIELDLAR ───────────────────────────────────────────────────

    /** Shoshilinchlik darajasi: URGENT yoki NORMAL (default: NORMAL) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    /**
     * Bo'lim: NORMAL (oddiy) yoki URGENT (shoshilinch).
     * Admin tomonidan belgilanadi.
     * null = hali bo'limga o'tkazilmagan.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "section")
    private ApplicationSection section;

    /**
     * Bajarish muddati — admin tomonidan belgilanadi.
     * NORMAL → +10 kun, URGENT → +5 kun (default)
     */
    @Column(name = "deadline")
    private LocalDateTime deadline;

    /** Telegram file_id — voice, document, photo uchun */
    private String fileId;

    /** Fayl turi: "voice" | "document" | "photo" | null */
    private String fileType;

    /** Admin javob bergan vaqt — ReminderService uchun */
    @Column(name = "replied_at")
    private LocalDateTime repliedAt;

    // ─────────────────────────────────────────────────────────────────────

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

}