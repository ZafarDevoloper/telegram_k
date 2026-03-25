package com.example.demo.repository;

import com.example.demo.entity.Application;
import com.example.demo.enums.ApplicationCategory;
import com.example.demo.enums.ApplicationSection;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.enums.Priority;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    // ─── Asosiy filter + pagination ───────────────────────────────────────
    @Query("""
        SELECT a FROM Application a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:lang   IS NULL OR a.lang   = :lang)
          AND (:search IS NULL OR :search  = ''
               OR LOWER(a.description)   LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(a.applicantName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR CAST(a.id AS string)   LIKE CONCAT('%', :search, '%'))
        ORDER BY a.submissionTime DESC
    """)
    Page<Application> findFiltered(
            @Param("status") ApplicationStatus status,
            @Param("lang")   String lang,
            @Param("search") String search,
            Pageable pageable
    );

    // ─── Foydalanuvchi murojaatlari ───────────────────────────────────────
    List<Application> findByChatIdOrderBySubmissionTimeDesc(String chatId);

    // ─── Status bo'yicha soni ─────────────────────────────────────────────
    long countByStatus(ApplicationStatus status);

    // ─── Priority bo'yicha soni ───────────────────────────────────────────
    long countByPriority(Priority priority);

    // ─── Kategoriya bo'yicha soni ─────────────────────────────────────────
    long countByCategory(ApplicationCategory category);

    // ─── Kategoriya bo'yicha ro'yxat ──────────────────────────────────────
    List<Application> findByCategoryOrderBySubmissionTimeDesc(ApplicationCategory category);

    // ─── Javobsiz murojaatlar (Reminder uchun) ────────────────────────────
    @Query("""
        SELECT a FROM Application a
        WHERE a.status IN ('PENDING', 'IN_REVIEW')
          AND a.submissionTime < :threshold
          AND a.adminReply IS NULL
    """)
    List<Application> findUnrepliedBefore(@Param("threshold") LocalDateTime threshold);

    // ─── Bugungi statistika ───────────────────────────────────────────────
    @Query("SELECT COUNT(a) FROM Application a WHERE a.submissionTime >= :since")
    long countSince(@Param("since") LocalDateTime since);

    // ─── Til bo'yicha taqsimot ────────────────────────────────────────────
    @Query("SELECT a.lang, COUNT(a) FROM Application a GROUP BY a.lang")
    List<Object[]> countGroupByLang();

    // ─── Kunlik statistika (oxirgi 7 kun) ─────────────────────────────────
    @Query("""
        SELECT CAST(a.submissionTime AS date), COUNT(a)
        FROM Application a
        WHERE a.submissionTime >= :since
        GROUP BY CAST(a.submissionTime AS date)
        ORDER BY CAST(a.submissionTime AS date)
    """)
    List<Object[]> countByDaySince(@Param("since") LocalDateTime since);

    // ─── Qo'shimcha murojaatlar ───────────────────────────────────────────
    List<Application> findByParentIdOrderBySubmissionTimeAsc(Long parentId);

    // ─── Shoshilinch + kutmoqda ───────────────────────────────────────────
    @Query("""
        SELECT a FROM Application a
        WHERE a.priority = 'URGENT'
          AND a.status = 'PENDING'
        ORDER BY a.submissionTime ASC
    """)
    List<Application> findUrgentPending();

    // ─── Fayli bor murojaatlar ────────────────────────────────────────────
    @Query("SELECT a FROM Application a WHERE a.fileId IS NOT NULL ORDER BY a.submissionTime DESC")
    List<Application> findAllWithFiles();

    // ─── Oylik hisobot ────────────────────────────────────────────────────
    @Query("""
    SELECT EXTRACT(MONTH FROM a.submissionTime), COUNT(a)
    FROM Application a
    WHERE EXTRACT(YEAR FROM a.submissionTime) = :year
    GROUP BY EXTRACT(MONTH FROM a.submissionTime)
    ORDER BY EXTRACT(MONTH FROM a.submissionTime)
""")
    List<Object[]> countByMonthForYear(@Param("year") int year);

    // ─── ChatId bo'yicha soni ─────────────────────────────────────────────
    @Query("SELECT COUNT(a) FROM Application a WHERE a.chatId = :chatId")
    long countByChatId(@Param("chatId") String chatId);

    // ─── Broadcast uchun noyob chatId lar ────────────────────────────────
    @Query("SELECT DISTINCT a.chatId FROM Application a WHERE a.chatId IS NOT NULL")
    List<String> findDistinctChatIds();

    // ─── Restart notify uchun: berilgan statuslardagi chatId lar ──────────
    @Query("SELECT DISTINCT a.chatId FROM Application a WHERE a.chatId IS NOT NULL AND a.status IN :statuses")
    List<String> findDistinctChatIdsByStatusIn(@Param("statuses") List<ApplicationStatus> statuses);

    // ─── [YANGI] Deadline o'tib ketgan murojaatlar ────────────────────────
    /**
     * DeadlineService ishlatadi.
     * Shartlar:
     *   - deadline belgilangan
     *   - deadline vaqti o'tib ketgan (now dan oldin)
     *   - hali yopilmagan (CLOSED emas, REPLIED emas)
     *   - deadlineNotified = false (xabar yuborilmagan)
     */
    @Query("""
        SELECT a FROM Application a
        WHERE a.deadline IS NOT NULL
          AND a.deadline < :now
          AND a.status NOT IN ('CLOSED', 'REPLIED')
          AND a.deadlineNotified = false
        ORDER BY a.deadline ASC
    """)
    List<Application> findOverdueApplications(@Param("now") LocalDateTime now);

    // ─── [YANGI] Yaqinlashayotgan deadline (24 soat qoldi) ───────────────
    @Query("""
        SELECT a FROM Application a
        WHERE a.deadline IS NOT NULL
          AND a.deadline BETWEEN :now AND :soon
          AND a.status NOT IN ('CLOSED', 'REPLIED')
          AND a.deadlineNotified = false
        ORDER BY a.deadline ASC
    """)
    List<Application> findUpcomingDeadlines(
            @Param("now")  LocalDateTime now,
            @Param("soon") LocalDateTime soon
    );

    @Query("""
    SELECT COUNT(a) FROM Application a
    WHERE a.section = :applicationSection
""")
    long countBySection(@Param("applicationSection") ApplicationSection applicationSection);

    @Query("""
    SELECT COUNT(a) FROM Application a
    WHERE a.deadline IS NOT NULL
      AND a.deadline < :now
      AND a.status NOT IN ('CLOSED', 'REPLIED')
      AND a.deadlineNotified = false
""")
    long countOverdueApplications(@Param("now") LocalDateTime now);
}