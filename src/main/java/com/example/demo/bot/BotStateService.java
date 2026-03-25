package com.example.demo.bot;

import com.example.demo.enums.BotState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BotStateService — Bot holatlari va foydalanuvchi ma'lumotlarini saqlaydi.
 *
 * O'zgarishlar (v8):
 *   - fullNames   — foydalanuvchi ism-familyasi (bir marta so'raladi)
 *   - phoneNumbers — foydalanuvchi telefon raqami (bir marta so'raladi)
 *   - hasStoredContact() — kontakt ma'lumotlari saqlanganligi tekshiruvi
 */
@Service
public class BotStateService {

    private final Map<String, BotState> states      = new ConcurrentHashMap<>();
    private final Map<String, Long>     stateTimes  = new ConcurrentHashMap<>();
    private final Map<String, String>   langs       = new ConcurrentHashMap<>();
    private final Map<String, Long>     lastAppIds  = new ConcurrentHashMap<>();
    private final Map<String, Long>     adminReply  = new ConcurrentHashMap<>();
    private final Map<String, Long>     chatByUser  = new ConcurrentHashMap<>();
    private final Map<String, String>   chatByAdmin = new ConcurrentHashMap<>();
    private final Map<String, String>   userNames   = new ConcurrentHashMap<>();
    private final Map<String, String>   fullNames   = new ConcurrentHashMap<>();
    private final Map<String, String>   phoneNumbers= new ConcurrentHashMap<>();
    private final Map<String, Long>     albumAppIds = new ConcurrentHashMap<>();
    private final Map<String, Long>     lastMsgTime = new ConcurrentHashMap<>();

    private final Set<String> knownUserChatIds = ConcurrentHashMap.newKeySet();

    private static final long STATE_TTL_MS      = 30L * 60 * 1000;
    private static final long FLOOD_INTERVAL_MS = 1000L;

    // ─── State ───────────────────────────────────────────────────────────

    public void setState(String chatId, BotState state) {
        states.put(chatId, state);
        stateTimes.put(chatId, System.currentTimeMillis());
    }

    public Optional<BotState> getState(String chatId) {
        BotState state = states.get(chatId);
        if (state == null) return Optional.empty();
        Long ts = stateTimes.get(chatId);
        if (ts != null && System.currentTimeMillis() - ts > STATE_TTL_MS) {
            removeState(chatId);
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public void removeState(String chatId) {
        states.remove(chatId);
        stateTimes.remove(chatId);
    }

    public boolean hasState(String chatId, BotState state) {
        return getState(chatId).map(s -> s == state).orElse(false);
    }

    // ─── Til ─────────────────────────────────────────────────────────────

    public void setLang(String chatId, String lang) {
        langs.put(chatId, lang);
    }

    public String getLang(String chatId) {
        return langs.getOrDefault(chatId, "uz");
    }

    public boolean hasStoredLang(String chatId) {
        return langs.containsKey(chatId);
    }

    // ─── Kontakt (ism-familya + telefon) ─────────────────────────────────

    public void setFullName(String chatId, String fullName) {
        fullNames.put(chatId, fullName);
    }

    public String getFullName(String chatId) {
        return fullNames.getOrDefault(chatId, "Anonim");
    }

    public void setPhoneNumber(String chatId, String phone) {
        phoneNumbers.put(chatId, phone);
    }

    public String getPhoneNumber(String chatId) {
        return phoneNumbers.getOrDefault(chatId, "");
    }

    /**
     * Foydalanuvchi ism-familya va telefon raqamini kiritganmi?
     * Ikkisi ham bo'lsa true qaytaradi.
     */
    public boolean hasStoredContact(String chatId) {
        return fullNames.containsKey(chatId) && phoneNumbers.containsKey(chatId);
    }

    // ─── Oxirgi murojaat ID ──────────────────────────────────────────────

    public void setLastAppId(String chatId, Long appId) {
        lastAppIds.put(chatId, appId);
    }

    public Optional<Long> getLastAppId(String chatId) {
        return Optional.ofNullable(lastAppIds.get(chatId));
    }

    // ─── Admin javob ─────────────────────────────────────────────────────

    public void setAdminReplyTarget(String adminChatId, Long appId) {
        adminReply.put(adminChatId, appId);
    }

    public Optional<Long> getAdminReplyTarget(String adminChatId) {
        return Optional.ofNullable(adminReply.get(adminChatId));
    }

    public void clearAdminReplyTarget(String adminChatId) {
        adminReply.remove(adminChatId);
    }

    // ─── Suhbat (live chat) ──────────────────────────────────────────────

    public void startChat(String adminChatId, String userChatId, Long appId) {
        chatByAdmin.put(adminChatId, userChatId);
        chatByUser.put(userChatId, appId);
    }

    public Optional<String> getChatTarget(String adminChatId) {
        return Optional.ofNullable(chatByAdmin.get(adminChatId));
    }

    public Optional<Long> getChatAppId(String userChatId) {
        return Optional.ofNullable(chatByUser.get(userChatId));
    }

    public boolean isUserInChat(String userChatId) {
        return chatByUser.containsKey(userChatId);
    }

    public void endChat(String adminChatId, String userChatId) {
        if (adminChatId != null) chatByAdmin.remove(adminChatId);
        chatByAdmin.entrySet().removeIf(e -> e.getValue().equals(userChatId));
        chatByUser.remove(userChatId);
    }

    // ─── Foydalanuvchi ismi (Telegram dan) ──────────────────────────────

    public void setUserName(String chatId, String name) {
        userNames.put(chatId, name);
        knownUserChatIds.add(chatId);
    }

    public String getUserName(String chatId) {
        return userNames.getOrDefault(chatId, "Anonim");
    }

    public Set<String> getKnownUserChatIds() {
        return Set.copyOf(knownUserChatIds);
    }

    // ─── Media album ─────────────────────────────────────────────────────

    public void setAlbumAppId(String mediaGroupId, Long appId) {
        albumAppIds.put(mediaGroupId, appId);
    }

    public Optional<Long> getAlbumAppId(String mediaGroupId) {
        return Optional.ofNullable(albumAppIds.get(mediaGroupId));
    }

    // ─── Flood nazorat ───────────────────────────────────────────────────

    public boolean isFlooding(String chatId) {
        long now  = System.currentTimeMillis();
        Long last = lastMsgTime.get(chatId);
        if (last != null && now - last < FLOOD_INTERVAL_MS) return true;
        lastMsgTime.put(chatId, now);
        return false;
    }

    // ─── To'liq tozalash ─────────────────────────────────────────────────

    public void clearAll(String chatId) {
        states.remove(chatId);
        stateTimes.remove(chatId);
        lastAppIds.remove(chatId);
        adminReply.remove(chatId);
        // langs, fullNames, phoneNumbers, userNames — saqlanadi
    }
}