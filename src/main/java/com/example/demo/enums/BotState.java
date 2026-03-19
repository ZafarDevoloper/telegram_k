package com.example.demo.enums;

/**
 * BotState — Bot holatlari.
 *
 * ConversationBot da String o'rniga enum ishlatiladi:
 *   - Xato nom yozilsa compile vaqtida tutiladi
 *   - IDE autocomplete ishlaydi
 *   - switch exhaustive tekshiruvi bor
 */
public enum BotState {
    /** Til tanlash kutilmoqda */
    LANGUAGE,
    /** Murojaat matni kutilmoqda */
    DESCRIPTION,
    /** Qo'shimcha ma'lumot kutilmoqda */
    ADDITIONAL,
    /** Admin javob matni kutilmoqda */
    ADMIN_REPLY,
    /** Foydalanuvchi live chat rejimida */
    CHAT,
    /** Admin broadcast matni kutilmoqda */
    BROADCAST
}