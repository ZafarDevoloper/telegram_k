package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * TelegramNotifyService — Circular dependency va AbsSender muammosini hal qiladi.
 *
 * Muammo:
 *   - @Lazy ConversationBot → tsikl xavfi
 *   - AbsSender to'g'ridan inject → ConversationBot Spring ga qanday
 *     ro'yxatdan o'tganiga bog'liq (TelegramBots lib o'zi boshqarishi mumkin)
 *
 * Yechim: ApplicationContext orqali lazy olish.
 *   - Bot tayyor bo'lganda getSender() topadi
 *   - Hech qanday circular dependency yo'q
 *   - ConversationBot qanday ro'yxatdan o'tishidan mustaqil
 */
@Service
public class TelegramNotifyService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifyService.class);

    private final ApplicationContext context;

    public TelegramNotifyService(ApplicationContext context) {
        this.context = context;
    }

    private AbsSender getSender() {
        try {
            return context.getBean(AbsSender.class);
        } catch (Exception e) {
            log.warn("AbsSender bean topilmadi (bot hali tayyor emas?): {}", e.getMessage());
            return null;
        }
    }

    public void sendMessage(String chatId, String text) {
        if (chatId == null || chatId.isBlank() || text == null || text.isBlank()) return;
        AbsSender sender = getSender();
        if (sender == null) return;
        try {
            sender.execute(new SendMessage(chatId, text));
        } catch (TelegramApiException e) {
            log.error("Xabar yuborishda xato [chatId={}]: {}", chatId, e.getMessage());
        }
    }

    public void sendMessage(String chatId, String text, InlineKeyboardMarkup markup) {
        if (chatId == null || chatId.isBlank() || text == null || text.isBlank()) return;
        AbsSender sender = getSender();
        if (sender == null) return;
        try {
            SendMessage msg = new SendMessage(chatId, text);
            if (markup != null) msg.setReplyMarkup(markup);
            sender.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Xabar yuborishda xato [chatId={}]: {}", chatId, e.getMessage());
        }
    }

    public void notifyAllAdmins(String adminIdsRaw, String text) {
        if (adminIdsRaw == null || adminIdsRaw.isBlank()) return;
        for (String id : adminIdsRaw.split(",")) {
            String adminId = id.trim();
            if (!adminId.isEmpty()) sendMessage(adminId, text);
        }
    }
}