package com.example.demo.enums;

/**
 * BotState — Bot holatlari.
 *
 * Yangi holatlari (v8.0):
 *   - NAME     — Foydalanuvchi ism-familyasini kiritmoqda
 *   - PHONE    — Telefon raqam kiritmoqda
 *   Avvalgisi: LANGUAGE → NAME → PHONE → DESCRIPTION
 */
public enum BotState {
    LANGUAGE,
    FULL_NAME,
    PHONE,
    DESCRIPTION,
    ADDITIONAL,
    ADMIN_REPLY,
    BROADCAST,
    CHAT,
    DEADLINE  // Admin muddatni kiritmoqda
}