package com.example.demo.service;

import com.example.demo.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LoginAttemptService — Brute force himoyasi.
 *
 * O'zgarishlar:
 *   - SLF4J logger (System.out o'rniga)
 *   - Mantiq o'zgarmadi — allaqachon to'g'ri yozilgan edi
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final int MAX_ATTEMPTS  = 5;
    private static final int BLOCK_MINUTES = 15;
    private static final int CLEANUP_HOURS = 24;

    private final Map<String, AttemptInfo> attempts = new ConcurrentHashMap<>();

    public void loginSuccess(String key) {
        attempts.remove(key);
    }

    public void loginFailed(String key) {
        AttemptInfo info = attempts.computeIfAbsent(key, k -> new AttemptInfo());
        info.count++;
        info.lastAttempt = LocalDateTime.now();

        if (info.count >= MAX_ATTEMPTS) {
            info.blockedUntil = LocalDateTime.now().plusMinutes(BLOCK_MINUTES);
            log.warn("Hisob bloklandi: {} ({} ta noto'g'ri urinish)", key, info.count);
        }
    }

    public void checkBlocked(String key) {
        AttemptInfo info = attempts.get(key);
        if (info == null) return;

        if (info.blockedUntil != null && LocalDateTime.now().isAfter(info.blockedUntil)) {
            attempts.remove(key);
            return;
        }

        if (info.blockedUntil != null) {
            long minutesLeft = java.time.Duration.between(
                    LocalDateTime.now(), info.blockedUntil).toMinutes() + 1;
            throw new TooManyRequestsException(
                    "Juda ko'p xato urinish! " + minutesLeft + " daqiqadan so'ng qayta urinib ko'ring.");
        }
    }

    public int getRemainingAttempts(String key) {
        AttemptInfo info = attempts.get(key);
        if (info == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - info.count);
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(CLEANUP_HOURS);
        int before = attempts.size();

        attempts.entrySet().removeIf(entry -> {
            AttemptInfo info = entry.getValue();
            if (info.blockedUntil != null && LocalDateTime.now().isAfter(info.blockedUntil)) {
                return true;
            }
            return info.lastAttempt != null
                    && info.lastAttempt.isBefore(threshold)
                    && info.blockedUntil == null;
        });

        int removed = before - attempts.size();
        if (removed > 0) {
            log.info("LoginAttemptService cleanup: {} eski yozuv o'chirildi. Qoldi: {}",
                    removed, attempts.size());
        }
    }

    public int getTrackedCount() { return attempts.size(); }

    public int getBlockedCount() {
        LocalDateTime now = LocalDateTime.now();
        return (int) attempts.values().stream()
                .filter(i -> i.blockedUntil != null && now.isBefore(i.blockedUntil))
                .count();
    }

    private static class AttemptInfo {
        int count = 0;
        LocalDateTime lastAttempt;
        LocalDateTime blockedUntil;
    }
}