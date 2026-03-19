package com.example.demo.repository;

import com.example.demo.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // ─── Murojaat bo'yicha barcha xabarlar (vaqt bo'yicha) ───────────────
    List<ChatMessage> findByAppIdOrderBySentAtAsc(Long appId);

    // ─── Murojaat bo'yicha xabarlar soni ──────────────────────────────────
    long countByAppId(Long appId);

    // ─── Foydalanuvchi xabarlarini olish ──────────────────────────────────
    List<ChatMessage> findByAppIdAndSenderTypeOrderBySentAtAsc(Long appId, String senderType);

    // ─── Oxirgi N ta xabar ────────────────────────────────────────────────
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.appId = :appId
        ORDER BY m.sentAt DESC
        LIMIT :limit
    """)
    List<ChatMessage> findLastNMessages(@Param("appId") Long appId, @Param("limit") int limit);

    // ─── Barcha chat bo'lgan murojaatlar ro'yxati ─────────────────────────
    @Query("SELECT DISTINCT m.appId FROM ChatMessage m WHERE m.senderType != 'SYSTEM'")
    List<Long> findAppIdsWithChat();

    // ─── O'chirish (murojaat o'chirilganda) ───────────────────────────────
    void deleteByAppId(Long appId);
}