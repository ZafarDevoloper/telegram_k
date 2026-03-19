package com.example.demo.controller;

import com.example.demo.service.ApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * StatsController — Dashboard uchun statistika endpointlari
 */
@RestController
@RequestMapping("/api/admin/stats")
public class StatsController {

    private final ApplicationService appService;

    public StatsController(ApplicationService appService) {
        this.appService = appService;
    }

    // ─── Asosiy dashboard statistikasi ───────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(appService.getStats());
    }
}