package com.example.demo.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JwtUtil — Access token + Refresh token.
 *
 * Xavfsizlik:
 *   - jwt.secret majburiy, 32+ belgi bo'lishi shart
 *   - Default qiymat yo'q — sozlanmasa server ishga tushmaydi
 *   - Access token:  24 soat
 *   - Refresh token: 7 kun
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long accessExpMs;

    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpMs;

    // ─── Startup validatsiya ──────────────────────────────────────────────
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret sozlanmagan! application.properties ga qo'shing: jwt.secret=<kamida 32 belgili tasodifiy kalit>");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "jwt.secret juda qisqa! Kamida " + MIN_SECRET_LENGTH + " ta belgi bo'lishi kerak. Hozir: " + secret.length());
        }
        log.info("JWT secret validatsiyadan o'tdi ({} belgi)", secret.length());
    }

    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    // ─── Access token ─────────────────────────────────────────────────────
    public String generateToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("type", "ACCESS");
        return build(claims, username, accessExpMs);
    }

    // ─── Refresh token ────────────────────────────────────────────────────
    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");
        return build(claims, username, refreshExpMs);
    }

    private String build(Map<String, Object> claims, String subject, long expMs) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expMs))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // ─── Username olish ───────────────────────────────────────────────────
    public String extractUsername(String token) {
        return getClaims(token).getSubject();
    }

    // ─── Role olish ───────────────────────────────────────────────────────
    public String extractRole(String token) {
        return (String) getClaims(token).get("role");
    }

    // ─── Access token tekshirish ──────────────────────────────────────────
    public boolean validateToken(String token) {
        try {
            Claims c = getClaims(token);
            return "ACCESS".equals(c.get("type")) && !c.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // ─── Refresh token tekshirish ─────────────────────────────────────────
    public boolean validateRefreshToken(String token) {
        try {
            Claims c = getClaims(token);
            return "REFRESH".equals(c.get("type")) && !c.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        try {
            return getClaims(token).getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}