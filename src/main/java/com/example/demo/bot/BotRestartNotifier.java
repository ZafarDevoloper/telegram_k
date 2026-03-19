package com.example.demo.bot;

import com.example.demo.repository.ApplicationRepository;
import com.example.demo.enums.ApplicationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.List;

/**
 * BotRestartNotifier — Bot restart bo'lganda faol murojaatlar egasiga xabar yuboradi.
 *
 * Muammo:
 *   BotStateService xotirada saqlaydi — restart bo'lsa state yo'qoladi.
 *   Foydalanuvchi murojaat jarayonida qolib ketadi, bot javob bermaydi.
 *
 * Yechim:
 *   ApplicationReadyEvent — server to'liq ishga tushganda DB dan
 *   PENDING/IN_REVIEW murojaatlar egasiga xabar yuboriladi.
 *
 * Sozlash (ixtiyoriy, default: true):
 *   bot.restart-notify.enabled=true
 */
@Component
public class BotRestartNotifier implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(BotRestartNotifier.class);

    private final ApplicationRepository repository;
    private final AbsSender             sender;

    @Value("${bot.restart-notify.enabled:true}")
    private boolean enabled;

    public BotRestartNotifier(ApplicationRepository repository, AbsSender sender) {
        this.repository = repository;
        this.sender     = sender;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled) return;

        try {
            // PENDING yoki IN_REVIEW holatidagi murojaatlar egalariga xabar
            List<String> chatIds = repository.findDistinctChatIdsByStatusIn(
                    List.of(ApplicationStatus.PENDING, ApplicationStatus.IN_REVIEW));

            if (chatIds.isEmpty()) return;

            log.info("Restart xabari yuborilmoqda: {} ta foydalanuvchi", chatIds.size());

            for (String chatId : chatIds) {
                try {
                    SendMessage msg = new SendMessage(chatId,
                            "Tizim yangilandi. Agar murojaat jarayonida bo'lsangiz, " +
                                    "/start bosib davom eting. Murojaatingiz saqlanib qolgan.");
                    sender.execute(msg);
                } catch (Exception e) {
                    log.warn("Restart xabari yuborilmadi [chatId={}]: {}", chatId, e.getMessage());
                }
            }
            log.info("Restart xabarlari yuborildi: {} ta", chatIds.size());
        } catch (Exception e) {
            log.error("BotRestartNotifier xatosi: {}", e.getMessage());
        }
    }
}