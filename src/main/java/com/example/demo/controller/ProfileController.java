package com.example.demo.controller;

import com.example.demo.entity.AdminUser;
import com.example.demo.repository.AdminUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ProfileController — Kirgan admin o'z profilini boshqaradi
 */
@RestController
@RequestMapping("/api/admin/profile")
public class ProfileController {

    private final AdminUserRepository adminRepo;
    private final PasswordEncoder     encoder;

    public ProfileController(AdminUserRepository adminRepo, PasswordEncoder encoder) {
        this.adminRepo = adminRepo;
        this.encoder   = encoder;
    }

    // ─── O'z profilini ko'rish ────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> getProfile(Authentication auth) {
        return adminRepo.findByUsername(auth.getName())
                .map(u -> ResponseEntity.ok(Map.of(
                        "id",       u.getId(),
                        "username", u.getUsername(),
                        "fullName", u.getFullName() != null ? u.getFullName() : "",
                        "role",     u.getRole().name(),
                        "active",   u.isActive()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── To'liq ismni yangilash ───────────────────────────────────────────
    @PatchMapping("/name")
    public ResponseEntity<?> updateName(Authentication auth,
                                        @RequestBody Map<String, String> body) {
        AdminUser user = adminRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("Admin topilmadi"));
        user.setFullName(body.get("fullName"));
        adminRepo.save(user);
        return ResponseEntity.ok(Map.of("message", "Ism yangilandi"));
    }

    // ─── Parolni o'zgartirish ─────────────────────────────────────────────
    @PatchMapping("/password")
    public ResponseEntity<?> changePassword(Authentication auth,
                                            @RequestBody Map<String, String> body) {
        AdminUser user = adminRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("Admin topilmadi"));

        String oldPass = body.get("oldPassword");
        String newPass = body.get("newPassword");

        if (!encoder.matches(oldPass, user.getPassword())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Eski parol noto'g'ri"));
        }
        if (newPass == null || newPass.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Yangi parol kamida 6 ta belgidan iborat bo'lishi kerak"));
        }

        user.setPassword(encoder.encode(newPass));
        adminRepo.save(user);
        return ResponseEntity.ok(Map.of("message", "Parol muvaffaqiyatli o'zgartirildi"));
    }
}