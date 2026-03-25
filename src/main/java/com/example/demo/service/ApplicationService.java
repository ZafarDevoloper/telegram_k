package com.example.demo.service;

import com.example.demo.entity.Application;
import com.example.demo.enums.ApplicationSection;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.enums.Priority;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ApplicationService — Murojaat biznes logikasi.
 *
 * O'zgarishlar (v8):
 *   - replyToApplication() → deadline bekor qilinadi
 *   - updateStatus() → priority o'zgarganda deadline qayta belgilanadi
 *   - saveWithDeadline() — yangi murojaat saqlashda deadline belgilanadi
 */
@Service
@Transactional(readOnly = true)
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository repository;
    private final DeadlineService        deadlineService;

    public ApplicationService(ApplicationRepository repository,
                              DeadlineService deadlineService) {
        this.repository      = repository;
        this.deadlineService = deadlineService;
    }

    // ─── Ro'yxat ─────────────────────────────────────────────────────────
    public Page<Application> getApplications(ApplicationStatus status,
                                             String lang,
                                             String search,
                                             int page,
                                             int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("submissionTime").descending());
        return repository.findFiltered(status, lang, search, pageable);
    }

    // ─── Bitta murojaat ───────────────────────────────────────────────────
    public Application getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", id));
    }

    // ─── Javob berish ─────────────────────────────────────────────────────
    @Transactional
    public Application replyToApplication(Long id, String replyText) {
        Application app = getById(id);
        app.setAdminReply(replyText);
        app.setStatus(ApplicationStatus.REPLIED);
        app.setRepliedAt(LocalDateTime.now());
        Application saved = repository.save(app);
        log.info("Murojaat #{} ga javob berildi", id);
        return saved;
    }

    // ─── Status o'zgartirish ──────────────────────────────────────────────
    @Transactional
    public Application updateStatus(Long id, ApplicationStatus newStatus) {
        Application app = getById(id);
        app.setStatus(newStatus);
        Application saved = repository.save(app);
        log.info("Murojaat #{} holati o'zgartirildi: {}", id, newStatus);
        return saved;
    }

    // ─── Priority o'zgartirish (deadline qayta hisoblanadi) ───────────────
    @Transactional
    public Application updatePriority(Long id, Priority newPriority) {
        Application app = getById(id);
        app.setPriority(newPriority);
        // Deadline qayta belgilanadi
        deadlineService.reassignDeadline(app);
        log.info("Murojaat #{} prioriteti o'zgartirildi: {} | yangi deadline: {}",
                id, newPriority, deadlineService.formatDeadline(app));
        return app;
    }

    // ─── Ko'rildi belgilash ───────────────────────────────────────────────
    @Transactional
    public void markAsViewed(Long id) {
        repository.findById(id).ifPresent(app -> {
            boolean changed = false;

            if (app.getStatus() == ApplicationStatus.PENDING) {
                app.setStatus(ApplicationStatus.IN_REVIEW);
                changed = true;
            }
            if (app.getViewedAt() == null) {
                app.setViewedAt(LocalDateTime.now());
                changed = true;
            }
            // Deadline yo'q bo'lsa — belgilanadi
            if (app.getDeadline() == null) {
                app.setDeadline(deadlineService.calculateDeadline(app));
                changed = true;
                log.debug("Murojaat #{} ga deadline belgilandi (markAsViewed)", id);
            }
            if (changed) {
                repository.save(app);
            }
        });
    }

    // ─── O'chirish ────────────────────────────────────────────────────────
    @Transactional
    public void deleteApplication(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Murojaat", id);
        }
        repository.deleteById(id);
        log.info("Murojaat #{} o'chirildi", id);
    }

    // ─── Deadline qo'lda yangilash (admin paneldan) ───────────────────────
    @Transactional
    public Application updateDeadline(Long id, LocalDateTime newDeadline) {
        Application app = getById(id);
        app.setDeadline(newDeadline);
        app.setDeadlineNotified(false);
        Application saved = repository.save(app);
        log.info("Murojaat #{} deadline qo'lda o'zgartirildi: {}", id, newDeadline);
        return saved;
    }

    // ─── Statistika ───────────────────────────────────────────────────────
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("total",    repository.count());
        stats.put("pending",  repository.countByStatus(ApplicationStatus.PENDING));
        stats.put("inReview", repository.countByStatus(ApplicationStatus.IN_REVIEW));
        stats.put("replied",  repository.countByStatus(ApplicationStatus.REPLIED));
        stats.put("closed",   repository.countByStatus(ApplicationStatus.CLOSED));
        stats.put("urgent",   repository.countByPriority(Priority.URGENT));

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        stats.put("today", repository.countSince(todayStart));

        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        stats.put("thisWeek", repository.countSince(weekAgo));

        // Deadline o'tib ketganlar soni
        stats.put("overdue", repository.countOverdueApplications(LocalDateTime.now()));

        List<Object[]> langStats = repository.countGroupByLang();
        Map<String, Long> langMap = new LinkedHashMap<>();
        for (Object[] row : langStats) langMap.put((String) row[0], (Long) row[1]);
        stats.put("byLang", langMap);

        List<Object[]> dailyData = repository.countByDaySince(weekAgo);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object[] row : dailyData) {
            daily.add(Map.of("date", row[0].toString(), "count", row[1]));
        }
        stats.put("daily", daily);

        return stats;
    }
    /**
     * Faqat muddatni o'zgartirish (admin ixtiyoriy).
     *
     * @param id   murojaat ID
     * @param days necha kun (1-365)
     * @return yangilangan murojaat
     */
    @Transactional
    public Application setDeadline(Long id, int days) {
        Application app = getById(id);
        app.setDeadline(LocalDateTime.now().plusDays(days));
        Application saved = repository.save(app);
        log.info("Murojaat #{} muddati yangilandi: +{} kun", id, days);
        return saved;
    }
    /**
     * Bo'lim belgilash + avtomatik muddat qo'yish.
     *
     * @param id      murojaat ID
     * @param section NORMAL yoki URGENT
     * @param days    muddat (kun): NORMAL=10, URGENT=5
     * @return yangilangan murojaat
     */
    @Transactional
    public Application assignSection(Long id, ApplicationSection section, int days) {
        Application app = getById(id);
        app.setSection(section);
        app.setDeadline(LocalDateTime.now().plusDays(days));
        Application saved = repository.save(app);
        log.info("Murojaat #{} → bo'lim: {}, muddat: +{} kun", id, section, days);
        return saved;
    }

}