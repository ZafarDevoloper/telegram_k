package com.example.demo.controller;

import com.example.demo.entity.SearchHistory;
import com.example.demo.repository.SearchHistoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SearchHistoryController — Qidiruv tarixi.
 *
 * Endpointlar:
 *   POST /api/admin/search-history          — Yangi qidiruvni saqlash
 *   GET  /api/admin/search-history/my       — O'z qidiruv tarixi
 *   GET  /api/admin/search-history/all      — Barcha adminlar tarixi
 *   GET  /api/admin/search-history/top      — Top qidiruv so'zlari
 *   DELETE /api/admin/search-history/my     — O'z tarixini o'chirish
 */
@RestController
@RequestMapping("/api/admin/search-history")
public class SearchHistoryController {

    private final SearchHistoryRepository histRepo;

    public SearchHistoryController(SearchHistoryRepository histRepo) {
        this.histRepo = histRepo;
    }

    // ─── Yangi qidiruvni saqlash (frontend avtomatik chaqiradi) ──────────
    @PostMapping
    public ResponseEntity<Void> save(@RequestBody Map<String, Object> body,
                                     Authentication auth) {
        SearchHistory h = new SearchHistory(
                auth.getName(),
                (String) body.get("status"),
                (String) body.get("lang"),
                (String) body.get("search"),
                body.get("count") != null
                        ? ((Number) body.get("count")).longValue() : null
        );
        histRepo.save(h);
        return ResponseEntity.ok().build();
    }

    // ─── O'z qidiruv tarixi ───────────────────────────────────────────────
    @GetMapping("/my")
    public ResponseEntity<List<SearchHistory>> myHistory(Authentication auth) {
        return ResponseEntity.ok(
                histRepo.findTop20ByAdminUsernameOrderBySearchedAtDesc(auth.getName()));
    }

    // ─── Barcha adminlar tarixi ───────────────────────────────────────────
    @GetMapping("/all")
    public ResponseEntity<List<SearchHistory>> allHistory() {
        return ResponseEntity.ok(histRepo.findTop50ByOrderBySearchedAtDesc());
    }

    // ─── Top qidiruv so'zlari ─────────────────────────────────────────────
    @GetMapping("/top")
    public ResponseEntity<?> topQueries() {
        List<Object[]> raw = histRepo.findTopSearchQueries();
        List<Map<String, Object>> result = raw.stream()
                .map(r -> Map.of("query", r[0], "count", r[1]))
                .toList();
        return ResponseEntity.ok(result);
    }

    // ─── O'z tarixini o'chirish ───────────────────────────────────────────
    @DeleteMapping("/my")
    public ResponseEntity<Void> clearMyHistory(Authentication auth) {
        histRepo.deleteByAdminUsername(auth.getName());
        return ResponseEntity.noContent().build();
    }
}