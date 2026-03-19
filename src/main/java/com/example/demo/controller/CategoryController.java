package com.example.demo.controller;

import com.example.demo.entity.Application;
import com.example.demo.enums.ApplicationCategory;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.ApplicationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/categories")
public class CategoryController {

    private final ApplicationRepository appRepo;

    public CategoryController(ApplicationRepository appRepo) {
        this.appRepo = appRepo;
    }

    // ─── Barcha kategoriyalar ─────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<Map<String, String>>> getAll() {
        List<Map<String, String>> list = Arrays.stream(ApplicationCategory.values())
                .map(c -> Map.of(
                        "code",  c.name(),
                        "label", c.getLabel(),
                        "key",   c.getCode()
                ))
                .toList();
        return ResponseEntity.ok(list);
    }

    // ─── Kategoriyani o'zgartirish ────────────────────────────────────────
    @PatchMapping("/{appId}")
    public ResponseEntity<?> setCategory(@PathVariable Long appId,
                                         @RequestBody Map<String, String> body) {
        // [FIX 4] ResourceNotFoundException → 404 (avval RuntimeException → 500 edi)
        Application app = appRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Murojaat", appId));
        try {
            ApplicationCategory cat = ApplicationCategory.valueOf(body.get("category"));
            app.setCategory(cat);
            appRepo.save(app);
            return ResponseEntity.ok(Map.of(
                    "message",  "Kategoriya yangilandi",
                    "category", cat.getLabel()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Noto'g'ri kategoriya: " + body.get("category")));
        }
    }

    // ─── Kategoriya statistikasi ──────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<List<Map<String, Object>>> stats() {
        List<Map<String, Object>> result = Arrays.stream(ApplicationCategory.values())
                .map(cat -> {
                    long count = appRepo.countByCategory(cat);
                    return Map.<String, Object>of(
                            "category", cat.name(),
                            "label",    cat.getLabel(),
                            "count",    count
                    );
                })
                .toList();
        return ResponseEntity.ok(result);
    }
}