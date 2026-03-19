package com.example.demo.controller;

import com.example.demo.config.SetupState;
import com.example.demo.dto.AdminUserDto;
import com.example.demo.entity.AdminUser;
import com.example.demo.exception.TooManyRequestsException;
import com.example.demo.repository.AdminUserRepository;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminUserRepository adminRepo;
    private final PasswordEncoder     encoder;
    private final JwtUtil             jwtUtil;
    private final LoginAttemptService loginAttempt;
    private final SetupState          setupState;

    public AdminAuthController(AdminUserRepository adminRepo,
                               PasswordEncoder encoder,
                               JwtUtil jwtUtil,
                               LoginAttemptService loginAttempt,
                               SetupState setupState) {
        this.adminRepo    = adminRepo;
        this.encoder      = encoder;
        this.jwtUtil      = jwtUtil;
        this.loginAttempt = loginAttempt;
        this.setupState   = setupState;
    }

    // ─── Setup: birinchi SUPER_ADMIN yaratish ────────────────────────────
    /**
     * Tizimda hech kim yo'q bo'lganda birinchi adminni yaratadi.
     * Birinchi so'rov → SUPER_ADMIN.
     * Keyingi so'rovlar → 409 Conflict.
     * SecurityConfig da bu endpoint PERMIT_ALL qilingan.
     */
    @PostMapping("/setup")
    public ResponseEntity<?> setup(@RequestBody Map<String, String> body) {
        if (!setupState.isSetupRequired()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Tizim allaqachon sozlangan. Bu endpoint o'chirilgan."));
        }

        String username = body.get("username");
        String password = body.get("password");
        String fullName = body.getOrDefault("fullName", "Super Admin");

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username kiritish shart"));
        }
        if (!username.matches("^[a-zA-Z0-9_]{3,20}$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username faqat harf, raqam va _ dan iborat, 3-20 belgi"));
        }
        if (password == null || password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Parol kamida 8 ta belgidan iborat bo'lishi kerak"));
        }

        // Ikki marta tekshirish — race condition dan himoya
        synchronized (this) {
            if (!setupState.isSetupRequired()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Tizim allaqachon sozlangan."));
            }
            if (adminRepo.existsByUsername(username)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Bu username band"));
            }

            AdminUser superAdmin = new AdminUser();
            superAdmin.setUsername(username);
            superAdmin.setPassword(encoder.encode(password));
            superAdmin.setFullName(fullName);
            superAdmin.setRole(AdminUser.AdminRole.SUPER_ADMIN);
            superAdmin.setActive(true);
            adminRepo.save(superAdmin);

            setupState.setSetupRequired(false);
            log.info("SUPER_ADMIN yaratildi: '{}'", username);
        }

        String token   = jwtUtil.generateToken(username, AdminUser.AdminRole.SUPER_ADMIN.name());
        String refresh = jwtUtil.generateRefreshToken(username);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message",      "SUPER_ADMIN muvaffaqiyatli yaratildi",
                "username",     username,
                "role",         "SUPER_ADMIN",
                "token",        token,
                "refreshToken", refresh
        ));
    }

    // ─── Login ────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() ||
                password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username va parol kiritish shart"));
        }

        String ip  = getClientIp(request);
        String key = ip + ":" + username;

        loginAttempt.checkBlocked(key);

        var userOpt = adminRepo.findByUsername(username);
        if (userOpt.isEmpty()) {
            loginAttempt.loginFailed(key);
            int left = loginAttempt.getRemainingAttempts(key);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error",             "Login yoki parol noto'g'ri",
                            "remainingAttempts", left
                    ));
        }

        AdminUser user = userOpt.get();

        if (!user.isActive()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Hisob bloklangan. Admin bilan bog'laning."));
        }

        if (!encoder.matches(password, user.getPassword())) {
            loginAttempt.loginFailed(key);
            int left = loginAttempt.getRemainingAttempts(key);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(buildLoginError(left));
        }

        loginAttempt.loginSuccess(key);
        log.info("Muvaffaqiyatli login: '{}' ({})", username, ip);

        String accessToken  = jwtUtil.generateToken(user.getUsername(), user.getRole().name());
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

        return ResponseEntity.ok(Map.of(
                "token",        accessToken,
                "refreshToken", refreshToken,
                "username",     user.getUsername(),
                "fullName",     user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "role",         user.getRole().name()
        ));
    }

    // ─── Token yangilash ──────────────────────────────────────────────────
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "refreshToken kerak"));
        }
        if (!jwtUtil.validateRefreshToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token yaroqsiz. Qayta kiring."));
        }

        String username = jwtUtil.extractUsername(refreshToken);
        var userOpt = adminRepo.findByUsername(username);
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Foydalanuvchi topilmadi yoki bloklangan"));
        }

        AdminUser user     = userOpt.get();
        String newAccess   = jwtUtil.generateToken(user.getUsername(), user.getRole().name());
        String newRefresh  = jwtUtil.generateRefreshToken(user.getUsername());

        return ResponseEntity.ok(Map.of(
                "token",        newAccess,
                "refreshToken", newRefresh,
                "username",     user.getUsername(),
                "role",         user.getRole().name()
        ));
    }

    // ─── Kim ekanligim ────────────────────────────────────────────────────
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Token yaroqsiz"));
        }
        var userOpt = adminRepo.findByUsername(auth.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Topilmadi"));
        }
        return ResponseEntity.ok(AdminUserDto.from(userOpt.get()));
    }

    // ─── Yangi admin (faqat SUPER_ADMIN) ─────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || !username.matches("^[a-zA-Z0-9_]{3,20}$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username faqat harf, raqam va _ dan iborat, 3-20 belgi"));
        }
        if (password == null || password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Parol kamida 8 ta belgidan iborat bo'lishi kerak"));
        }
        if (adminRepo.existsByUsername(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bu username band"));
        }

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setFullName(body.get("fullName"));
        user.setRole(AdminUser.AdminRole.valueOf(
                body.getOrDefault("role", "OPERATOR")));
        adminRepo.save(user);

        log.info("Yangi admin yaratildi: '{}' ({})", username, user.getRole());
        return ResponseEntity.ok(Map.of("message", "Admin yaratildi: " + username));
    }

    // ─── Setup holati (frontend uchun) ───────────────────────────────────
    @GetMapping("/setup-status")
    public ResponseEntity<?> setupStatus() {
        return ResponseEntity.ok(Map.of("setupRequired", setupState.isSetupRequired()));
    }

    // ─── Yordamchi metodlar ───────────────────────────────────────────────
    private Map<String, Object> buildLoginError(int left) {
        if (left <= 2) {
            return Map.of(
                    "error",             "Login yoki parol noto'g'ri",
                    "remainingAttempts", left,
                    "warning",           left + " ta urinish qoldi! Keyin 15 daqiqa bloklanasiz."
            );
        }
        return Map.of(
                "error",             "Login yoki parol noto'g'ri",
                "remainingAttempts", left
        );
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        return ip;
    }
}