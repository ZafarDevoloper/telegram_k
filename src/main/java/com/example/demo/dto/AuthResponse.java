package com.example.demo.dto;

// ─── Login javobi ─────────────────────────────────────────────────────────────
public record AuthResponse(
        String token,
        String username,
        String fullName,
        String role
) {}