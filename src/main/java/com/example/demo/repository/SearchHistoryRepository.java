package com.example.demo.repository;

import com.example.demo.entity.SearchHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Long> {

    // Admin bo'yicha oxirgi qidiruvlar
    List<SearchHistory> findTop20ByAdminUsernameOrderBySearchedAtDesc(String username);

    // Barcha adminlar — oxirgi 50 ta
    List<SearchHistory> findTop50ByOrderBySearchedAtDesc();

    // Eng ko'p ishlatiladigan qidiruv so'zlari
    @Query("""
        SELECT s.searchQuery, COUNT(s)
        FROM SearchHistory s
        WHERE s.searchQuery IS NOT NULL AND s.searchQuery != ''
        GROUP BY s.searchQuery
        ORDER BY COUNT(s) DESC
        LIMIT 10
    """)
    List<Object[]> findTopSearchQueries();

    // Admin bo'yicha o'chirish
    void deleteByAdminUsername(String username);
}