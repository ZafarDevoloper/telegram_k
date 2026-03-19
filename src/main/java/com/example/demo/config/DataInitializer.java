package com.example.demo.config;

import com.example.demo.entity.AdminUser;
import com.example.demo.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * DataInitializer — Agar tizimda hech bir admin bo'lmasa,
 * birinchi kirgan foydalanuvchi avtomatik SUPER_ADMIN bo'ladi.
 *
 * Logika:
 *   - Startup da adminlar soni tekshiriladi
 *   - Agar 0 ta bo'lsa → "setup" rejimi yoqiladi
 *   - /api/auth/setup endpointiga birinchi so'rov → SUPER_ADMIN yaratiladi
 *   - Keyingi so'rovlar → 409 Conflict (allaqachon setup qilingan)
 *
 * Hardcoded default parol YO'Q.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AdminUserRepository adminRepo;
    private final SetupState          setupState;

    public DataInitializer(AdminUserRepository adminRepo, SetupState setupState) {
        this.adminRepo  = adminRepo;
        this.setupState = setupState;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = adminRepo.count();
        if (count == 0) {
            setupState.setSetupRequired(true);
            log.warn("==========================================================");
            log.warn("  DIQQAT: Tizimda admin yo'q!");
            log.warn("  Birinchi admin yaratish uchun:");
            log.warn("  POST /api/auth/setup");
            log.warn("  Body: {\"username\":\"...\", \"password\":\"...\", \"fullName\":\"...\"}");
            log.warn("  Birinchi murojaat qilgan foydalanuvchi SUPER_ADMIN bo'ladi.");
            log.warn("==========================================================");
        } else {
            setupState.setSetupRequired(false);
            log.info("Tizimda {} ta admin mavjud. Setup rejimi o'chirilgan.", count);
        }
    }
}