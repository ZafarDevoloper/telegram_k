package com.example.demo.controller;

import com.example.demo.dto.AdminUserDto;
import com.example.demo.entity.AdminUser;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final AdminUserRepository adminRepo;
    private final PasswordEncoder     encoder;

    public AdminUserController(AdminUserRepository adminRepo, PasswordEncoder encoder) {
        this.adminRepo = adminRepo;
        this.encoder   = encoder;
    }

    // ─── Barcha adminlar (DTO orqali — parol chiqmaydi) ──────────────────
    @GetMapping
    public ResponseEntity<List<AdminUserDto>> getAll() {
        List<AdminUserDto> list = adminRepo.findAll()
                .stream()
                .map(AdminUserDto::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    // ─── Yangi admin yaratish ─────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String fullName = body.getOrDefault("fullName", "");
        String roleStr  = body.getOrDefault("role", "OPERATOR");

        // Validatsiya
        if (username == null || !username.matches("^[a-zA-Z0-9_]{3,20}$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username faqat harf, raqam va _ dan iborat, 3-20 belgi"));
        }
        if (password == null || password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Parol kamida 8 ta belgidan iborat bo'lishi kerak"));
        }
        if (adminRepo.existsByUsername(username)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bu username allaqachon mavjud"));
        }

        AdminUser.AdminRole role;
        try {
            role = AdminUser.AdminRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Noto'g'ri role: " + roleStr));
        }

        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPassword(encoder.encode(password));
        admin.setFullName(fullName);
        admin.setRole(role);
        admin.setActive(true);
        AdminUser saved = adminRepo.save(admin);

        log.info("Yangi admin yaratildi: '{}' ({})", username, role);
        return ResponseEntity.ok(AdminUserDto.from(saved));
    }

    // ─── Parolni o'zgartirish ─────────────────────────────────────────────
    @PatchMapping("/{id}/password")
    public ResponseEntity<?> changePassword(
            @PathVariable Long id,
            @RequestBody  Map<String, String> body
    ) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Parol kamida 8 ta belgidan iborat bo'lishi kerak"));
        }

        AdminUser admin = adminRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Admin", id));
        admin.setPassword(encoder.encode(newPassword));
        adminRepo.save(admin);

        log.info("Admin '{}' paroli yangilandi", admin.getUsername());
        return ResponseEntity.ok(Map.of("message", "Parol yangilandi"));
    }

    // ─── Bloklash / aktivlashtirish ───────────────────────────────────────
    @PatchMapping("/{id}/active")
    public ResponseEntity<?> toggleActive(
            @PathVariable Long id,
            @RequestBody  Map<String, Boolean> body
    ) {
        AdminUser admin = adminRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Admin", id));

        Boolean activeValue = body.get("active");
        if (activeValue == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "'active' maydoni kerak"));
        }

        admin.setActive(activeValue);
        adminRepo.save(admin);

        String status = admin.isActive() ? "Faollashtirildi" : "Bloklandi";
        log.info("Admin '{}': {}", admin.getUsername(), status);
        return ResponseEntity.ok(Map.of("message", status));
    }

    // ─── O'chirish ────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!adminRepo.existsById(id)) {
            throw new ResourceNotFoundException("Admin", id);
        }
        adminRepo.deleteById(id);
        log.info("Admin #{} o'chirildi", id);
        return ResponseEntity.noContent().build();
    }
}