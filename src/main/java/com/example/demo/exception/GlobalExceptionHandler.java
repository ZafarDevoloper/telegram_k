package com.example.demo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — (K4 fix) To'g'ri HTTP status kodlari.
 *
 * MUAMMO: Avvalgi versiyada RuntimeException → 400.
 * Lekin "Murojaat topilmadi" ham RuntimeException edi → 400 (noto'g'ri, 404 bo'lishi kerak).
 *
 * YECHIM:
 *   - ResourceNotFoundException → 404
 *   - IllegalArgumentException  → 400
 *   - RuntimeException (boshqa) → 500 (endi 400 emas!)
 *   - Exception (generic)       → 500
 *
 * Eslatma: getById() va boshqa metodlarda RuntimeException o'rniga
 * ResourceNotFoundException ishlating:
 *   throw new ResourceNotFoundException("Application", id);
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─── 404 — Resurs topilmadi ───────────────────────────────────────────
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "Not Found", e.getMessage());
    }

    // ─── 400 — Noto'g'ri argument ─────────────────────────────────────────
    // [K4 FIX] IllegalArgumentException → 400 (mantiqiy)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage());
    }

    // ─── 400 — Validation xatolari (@Valid) ──────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = base(HttpStatus.BAD_REQUEST, "Validation Error",
                "Maydonlarda xatoliklar mavjud");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    // ─── 400 — Noto'g'ri parametr turi ───────────────────────────────────
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request",
                "'" + e.getName() + "' parametri noto'g'ri tur: " + e.getValue());
    }

    // ─── 401 — Autentifikatsiya xatosi ───────────────────────────────────
    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    public ResponseEntity<Map<String, Object>> handleAuth(Exception e) {
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Login yoki parol noto'g'ri");
    }

    // ─── 403 — Ruxsat yo'q ────────────────────────────────────────────────
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "Forbidden",
                "Bu amalni bajarishga ruxsatingiz yo'q");
    }

    // ─── 429 — Ko'p so'rov (Rate limit) ──────────────────────────────────
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(TooManyRequestsException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", e.getMessage());
    }

    // ─── 500 — Kutilmagan RuntimeException ───────────────────────────────
    // [K4 FIX] RuntimeException → 500, 400 emas!
    // Agar biznes mantig'i uchun 400 kerak bo'lsa — IllegalArgumentException ishlating.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        // Log qilish (production da logger ishlatish tavsiya etiladi)
        System.err.println("[RuntimeException] " + e.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Server xatosi yuz berdi. Iltimos qayta urinib ko'ring.");
    }

    // ─── 500 — Umumiy server xatosi ──────────────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        e.printStackTrace();
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Server xatosi yuz berdi. Iltimos qayta urinib ko'ring.");
    }

    // ─── Yordamchi metodlar ───────────────────────────────────────────────
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(base(status, error, message));
    }

    private Map<String, Object> base(HttpStatus status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status",  status.value());
        body.put("error",   error);
        body.put("message", message != null ? message : "Noma'lum xato");
        body.put("time",    LocalDateTime.now().format(FMT));
        return body;
    }
}