package com.example.demo.service;

import com.example.demo.entity.Application;
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
 * O'zgarishlar:
 *   - markAsViewed() da ikki marta DB query yo'qoldi (avval getById() + save edi)
 *   - Barcha metodlarda ResourceNotFoundException → to'g'ri 404
 *   - SLF4J logger qo'shildi
 *   - @Transactional(readOnly=true) klass darajasida, write metodlarda override
 */
@Service
@Transactional(readOnly = true)
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository repository;

    public ApplicationService(ApplicationRepository repository) {
        this.repository = repository;
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

    // ─── Ko'rildi belgilash ───────────────────────────────────────────────
    /**
     * Ikki marta DB query muammosi hal qilindi:
     * Eski: getById() (1-query) → save (2-query)
     * Yangi: findById (1-query) → faqat o'zgarsa save (2-query yoki yo'q)
     */
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
}