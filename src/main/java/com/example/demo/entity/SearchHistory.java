package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SearchHistory — Admin qanday filtrlar ishlatganini saqlash.
 */
@Entity
@Table(name = "search_history")
public class SearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String adminUsername;

    private String statusFilter;
    private String langFilter;

    @Column(columnDefinition = "TEXT")
    private String searchQuery;

    private Long    resultsCount;
    private LocalDateTime searchedAt = LocalDateTime.now();

    // ─── Constructors ─────────────────────────────────────────────────────
    public SearchHistory() {}

    public SearchHistory(String adminUsername, String statusFilter,
                         String langFilter, String searchQuery, Long resultsCount) {
        this.adminUsername = adminUsername;
        this.statusFilter  = statusFilter;
        this.langFilter    = langFilter;
        this.searchQuery   = searchQuery;
        this.resultsCount  = resultsCount;
        this.searchedAt    = LocalDateTime.now();
    }

    // ─── Getters & Setters ────────────────────────────────────────────────
    public Long getId()                    { return id; }
    public String getAdminUsername()       { return adminUsername; }
    public void setAdminUsername(String v) { this.adminUsername = v; }
    public String getStatusFilter()        { return statusFilter; }
    public void setStatusFilter(String v)  { this.statusFilter = v; }
    public String getLangFilter()          { return langFilter; }
    public void setLangFilter(String v)    { this.langFilter = v; }
    public String getSearchQuery()         { return searchQuery; }
    public void setSearchQuery(String v)   { this.searchQuery = v; }
    public Long getResultsCount()          { return resultsCount; }
    public void setResultsCount(Long v)    { this.resultsCount = v; }
    public LocalDateTime getSearchedAt()   { return searchedAt; }
    public void setSearchedAt(LocalDateTime v){ this.searchedAt = v; }
}