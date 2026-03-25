package com.example.demo.bot;

import com.example.demo.enums.ApplicationSection;
import com.example.demo.enums.ApplicationStatus;
import com.example.demo.enums.BotState;
import com.example.demo.enums.Priority;
import com.example.demo.entity.Application;
import com.example.demo.entity.ChatMessage;
import com.example.demo.repository.ApplicationRepository;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.service.ExportService;
import com.example.demo.service.ReminderService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.games.Animation;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.*;
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConversationBot v8.0
 *
 * Yangiliklar (v7.0 → v8.0):
 *   1. Foydalanuvchi oqimi: Til → Ism-familya → Telefon → Murojaat tavsifi
 *      (avval: Til → Murojaat tavsifi)
 *   2. Admin bo'lim belgilash:
 *      - Har bir yangi murojaatda admin "Oddiy bo'lim" yoki "Shoshilinch bo'lim"
 *        tugmasini bosadi
 *      - Tanlangandan keyin muddat avtomatik qo'yiladi:
 *          NORMAL  → +10 kun
 *          URGENT  → +5  kun
 *      - Admin ixtiyoriy ravishda muddatni o'zi ham kirita oladi (/setdeadline ID KUN)
 *   3. Foydalanuvchiga bo'lim + muddat haqida bildiriladi
 *   4. CB_SECTION_NRM / CB_SECTION_URG yangi callback konstantlari
 */
@Component
public class ConversationBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(ConversationBot.class);

    // ─── Dependencies ──────────────────────────────────────────────────────
    private final ApplicationRepository  repository;
    private final ChatMessageRepository  chatMessageRepository;
    private final ExportService          exportService;
    private final ReminderService        reminderService;
    private final BotStateService        state;
    private final I18nService            i18n;

    // ─── Config ────────────────────────────────────────────────────────────
    @Value("${telegram.bot.token}")      private String botToken;
    @Value("${telegram.bot.username}")   private String botUsername;
    @Value("${telegram.admin.chat.ids}") private String adminChatIdsRaw;

    private final Set<String> adminChatIds = ConcurrentHashMap.newKeySet();

    // ─── Konstantalar ──────────────────────────────────────────────────────
    private static final long MAX_FILE_SIZE_MB    = 50L;
    private static final long MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024;
    private static final long CHAT_TIMEOUT_MS     = 30L * 60 * 1000;

    /** Muddat (kun): NORMAL bo'lim */
    private static final int DEADLINE_NORMAL_DAYS = 10;
    /** Muddat (kun): URGENT (shoshilinch) bo'lim */
    private static final int DEADLINE_URGENT_DAYS = 5;

    // ─── Callback konstantlari ─────────────────────────────────────────────
    private static final String CB_CHECK_STATUS  = "check_";
    private static final String CB_ADD_MORE      = "add_more";
    private static final String CB_EXPORT_ALL    = "export_word";
    private static final String CB_EXPORT_ONE    = "export_one_";
    private static final String CB_VIEWED        = "viewed_";
    private static final String CB_REPLY         = "reply_";
    private static final String CB_PRIORITY_URG  = "prio_urgent_";
    private static final String CB_PRIORITY_NRM  = "prio_normal_";
    private static final String CB_MY_APPS       = "my_apps";
    private static final String CB_START_CHAT    = "start_chat_";
    private static final String CB_END_CHAT      = "end_chat_";
    private static final String CB_FORWARD_FILE  = "fwd_file_";
    private static final String CB_CLOSE_APP     = "close_app_";
    /** [YANGI] Bo'lim callback-lari */
    private static final String CB_SECTION_NRM   = "sect_normal_";
    private static final String CB_SECTION_URG   = "sect_urgent_";

    // ─── Chat timeout tracking ─────────────────────────────────────────────
    private final Map<String, Long> chatLastActivity = new ConcurrentHashMap<>();

    // ─── Admin: muddat kiritish uchun qaysi appId kutilmoqda ──────────────
    // key = adminChatId, value = appId
    private final Map<String, Long> pendingDeadlineInput = new ConcurrentHashMap<>();

    // ─── Constructor ───────────────────────────────────────────────────────
    public ConversationBot(ApplicationRepository repository,
                           ChatMessageRepository chatMessageRepository,
                           ExportService exportService,
                           ReminderService reminderService,
                           BotStateService state,
                           I18nService i18n) {
        this.repository            = repository;
        this.chatMessageRepository = chatMessageRepository;
        this.exportService         = exportService;
        this.reminderService       = reminderService;
        this.state                 = state;
        this.i18n                  = i18n;
    }

    @PostConstruct
    public void init() {
        reminderService.setSender(this);
        Arrays.stream(adminChatIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(adminChatIds::add);

        Thread timeoutThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5 * 60 * 1000L);
                    checkChatTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[ConversationBot] checkChatTimeouts xato: {}", e.getMessage());
                }
            }
        });
        timeoutThread.setName("chat-timeout-checker");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        log.info("[ConversationBot] Ishga tushdi. Admin soni: {}", adminChatIds.size());
    }

    @Override public String getBotUsername() { return botUsername; }
    @Override public String getBotToken()    { return botToken; }

    private boolean isAdmin(String chatId) { return adminChatIds.contains(chatId); }

    // ═══════════════════════════════════════════════════════════════════════
    //  ANA KIRISH NUQTASI
    // ═══════════════════════════════════════════════════════════════════════
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
                return;
            }
            if (!update.hasMessage()) return;

            Message message = update.getMessage();
            String  chatId  = message.getChatId().toString();

            saveUserName(chatId, message.getFrom());

            if (!isAdmin(chatId) && state.isFlooding(chatId)) {
                sendMessage(chatId, i18n.get(state.getLang(chatId), "flood_warning"));
                return;
            }

            if (isAdmin(chatId)) {
                handleAdminMessage(message, chatId);
                return;
            }

            // Aktiv suxbat rejimi
            if (state.hasState(chatId, BotState.CHAT)) {
                chatLastActivity.put(chatId, System.currentTimeMillis());
                handleUserChatMessage(chatId, message);
                return;
            }

            if (message.hasText()) {
                String text = message.getText().trim();
                String lang = state.getLang(chatId);

                switch (text.toLowerCase()) {
                    case "/start" -> {
                        state.clearAll(chatId);
                        if (state.hasStoredLang(chatId)) {
                            // Til tanlangan — ism so'rash
                            state.setState(chatId, BotState.FULL_NAME);
                            sendMessage(chatId, i18n.get(lang, "ask_fullname"));
                        } else {
                            sendLanguageSelection(chatId);
                            state.setState(chatId, BotState.LANGUAGE);
                        }
                        return;
                    }
                    case "/myapps"          -> { sendMyApplications(chatId); return; }
                    case "/word", "/export" -> { handleWordExportAll(chatId); return; }
                    case "/cancel"          -> { handleCancel(chatId); return; }
                    case "/status"          -> { handleQuickStatus(chatId); return; }
                    case "/help"            -> { sendMessage(chatId, i18n.get(lang, "help")); return; }
                }

                BotState currentState = state.getState(chatId).orElse(BotState.LANGUAGE);
                switch (currentState) {
                    case LANGUAGE    -> handleLanguageSelection(chatId, text);
                    case FULL_NAME        -> handleNameInput(chatId, text);
                    case PHONE       -> handlePhoneInput(chatId, text);
                    case DESCRIPTION -> handleDescription(chatId, text);
                    case ADDITIONAL  -> handleAdditional(chatId, text);
                    default          -> sendMessage(chatId, i18n.get(lang, "restart"));
                }
                return;
            }

            handleIncomingMedia(chatId, message);

        } catch (Exception e) {
            handleError(update, e);
        }
    }

    // ─── Global error handler ─────────────────────────────────────────────
    private void handleError(Update update, Exception e) {
        log.error("[ConversationBot] Xatolik: {}", e.getMessage(), e);
        try {
            if (update.hasMessage()) {
                String chatId = update.getMessage().getChatId().toString();
                String lang   = state.getLang(chatId);
                sendMessage(chatId, isAdmin(chatId)
                        ? "Xatolik: " + e.getMessage()
                        : i18n.get(lang, "error_user"));
            }
        } catch (Exception ignored) {}
    }

    // ─── Foydalanuvchi ismini saqlash ─────────────────────────────────────
    private void saveUserName(String chatId, User from) {
        if (from == null) return;
        String name = from.getFirstName() != null ? from.getFirstName() : "";
        if (from.getLastName() != null) name += " " + from.getLastName();
        if (!name.isBlank()) state.setUserName(chatId, name.trim());
        else if (from.getUserName() != null) state.setUserName(chatId, "@" + from.getUserName());
    }

    // ─── /cancel ──────────────────────────────────────────────────────────
    private void handleCancel(String chatId) {
        String lang = state.getLang(chatId);
        BotState cur = state.getState(chatId).orElse(null);
        if (cur == null || cur == BotState.LANGUAGE) {
            sendMessage(chatId, i18n.get(lang, "nothing_to_cancel"));
            return;
        }
        state.clearAll(chatId);
        sendMessage(chatId, i18n.get(lang, "cancelled"));
    }

    // ─── /status ──────────────────────────────────────────────────────────
    private void handleQuickStatus(String chatId) {
        String lang = state.getLang(chatId);
        state.getLastAppId(chatId).ifPresentOrElse(
                id -> showApplicationStatus(chatId, id),
                () -> sendMessage(chatId, i18n.get(lang, "status_not_found"))
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  [YANGI] ISM VA TELEFON OQIMI
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * BotState.NAME — foydalanuvchi ism-familyasini kiritadi.
     * Ism validatsiya: kamida 2 ta belgi, matn bo'lishi shart.
     */
    private void handleNameInput(String chatId, String text) throws TelegramApiException {
        String lang = state.getLang(chatId);
        if (text == null || text.trim().length() < 2) {
            sendMessage(chatId, i18n.get(lang, "ask_fullname"));
            return;
        }
        // Ismni state ga saqlash (keyinchalik application ga yoziladi)
        state.setFullName(chatId, text.trim());   // fullNames map ga yozadi ✅
        // Keyingi qadam: telefon
        state.setState(chatId, BotState.PHONE);

        // Telefon raqamni tugma bilan ham yuborish imkoni
        SendMessage msg = new SendMessage(chatId, i18n.get(lang, "ask_phone"));
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(true);
        KeyboardRow row = new KeyboardRow();
        KeyboardButton phoneBtn = new KeyboardButton();
        phoneBtn.setText(switch (lang) {
            case "en" -> "📞 Share my phone number";
            case "ru" -> "📞 Поделиться номером";
            default   -> "📞 Telefon raqamimni yuborish";
        });
        phoneBtn.setRequestContact(true);
        row.add(phoneBtn);
        markup.setKeyboard(List.of(row));
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    /**
     * BotState.PHONE — telefon raqamni qabul qiladi.
     * Kontakt tugmasi YOKI qo'lda kiritilgan raqam qabul qilinadi.
     */
    private void handlePhoneInput(String chatId, String text) throws TelegramApiException {
        String lang = state.getLang(chatId);
        String phone = extractPhone(text);
        if (phone == null) {
            sendMessage(chatId, i18n.get(lang, "invalid_phone"));
            return;
        }
        // Telefon raqamni saqlash (BotStateService da yangi method)
        state.setPhoneNumber(chatId, phone);

        // Keyingi qadam: murojaat tavsifi
        state.setState(chatId, BotState.DESCRIPTION);

        SendMessage msg = new SendMessage(chatId, i18n.get(lang, "ask_desc"));
        msg.setReplyMarkup(new ReplyKeyboardRemove(true));
        execute(msg);
    }

    /**
     * Xabar kontakt bo'lsa — telefon undan olinadi.
     * Matn bo'lsa — regex orqali tekshiriladi.
     * Noto'g'ri format → null.
     */
    private String extractPhone(String text) {
        if (text == null) return null;
        // +998XXXXXXXXX yoki 998XXXXXXXXX yoki 0XXXXXXXXX formatlarini qabul qiladi
        String cleaned = text.replaceAll("\\s+", "");
        if (cleaned.matches("^\\+?[0-9]{9,15}$")) return cleaned;
        return null;
    }

    /**
     * Kontakt xabari orqali telefon yuborilganda.
     * onUpdateReceived da media handler ga o'tmasdan bu yerga yo'naltiriladi.
     */
    private void handleContactAsPhone(String chatId, Contact contact) throws TelegramApiException {
        String lang = state.getLang(chatId);
        if (!state.hasState(chatId, BotState.PHONE)) return;

        String phone = contact.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            sendMessage(chatId, i18n.get(lang, "invalid_phone"));
            return;
        }
        state.setPhoneNumber(chatId, phone);
        state.setState(chatId, BotState.DESCRIPTION);

        SendMessage msg = new SendMessage(chatId, i18n.get(lang, "ask_desc"));
        msg.setReplyMarkup(new ReplyKeyboardRemove(true));
        execute(msg);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MEDIA XABARLARNI QABUL QILISH
    // ═══════════════════════════════════════════════════════════════════════
    private void handleIncomingMedia(String chatId, Message message) throws TelegramApiException {
        String lang = state.getLang(chatId);
        BotState currentState = state.getState(chatId).orElse(null);

        // [YANGI] Agar PHONE holatida kontakt yuborganbo'lsa
        if (currentState == BotState.PHONE && message.hasContact()) {
            handleContactAsPhone(chatId, message.getContact());
            return;
        }

        if (currentState != BotState.DESCRIPTION && currentState != BotState.ADDITIONAL) {
            sendMessage(chatId, i18n.get(lang, "send_file_hint"));
            state.setState(chatId, BotState.DESCRIPTION);
            return;
        }

        if (message.getMediaGroupId() != null) {
            handleMediaAlbum(chatId, message, currentState, lang);
            return;
        }

        MediaInfo media = extractMediaInfo(message);
        if (media == null) {
            sendMessage(chatId, i18n.get(lang, "unsupported_media"));
            return;
        }
        if (media.fileSize > MAX_FILE_SIZE_BYTES) {
            sendMessage(chatId, getFileSizeErrorText(lang, MAX_FILE_SIZE_MB));
            return;
        }

        Long parentId = (currentState == BotState.ADDITIONAL)
                ? state.getLastAppId(chatId).orElse(null) : null;

        String description = buildMediaDescription(media, message.getCaption());
        Application app = saveApplication(chatId, lang, description, parentId, media.fileId, media.type);
        state.setLastAppId(chatId, app.getId());
        state.removeState(chatId);

        sendMessage(chatId, getMediaConfirmText(lang, media.typeLabel, media.fileName, media.fileSizeStr));
        sendPrioritySelection(chatId, lang, app.getId());
    }

    // ─── Media album ──────────────────────────────────────────────────────
    private void handleMediaAlbum(String chatId, Message message,
                                  BotState currentState, String lang) throws TelegramApiException {
        String groupId = message.getMediaGroupId();
        Optional<Long> existingAppId = state.getAlbumAppId(groupId);

        MediaInfo media = extractMediaInfo(message);
        if (media == null) return;

        if (existingAppId.isPresent()) {
            saveChatMessage(existingAppId.get(), "USER", chatId,
                    "[Album qo'shimcha] " + buildMediaDescription(media, message.getCaption()));
            return;
        }

        Long parentId = (currentState == BotState.ADDITIONAL)
                ? state.getLastAppId(chatId).orElse(null) : null;
        Application app = saveApplication(chatId, lang,
                "[Album] " + buildMediaDescription(media, message.getCaption()),
                parentId, media.fileId, media.type);
        state.setAlbumAppId(groupId, app.getId());
        state.setLastAppId(chatId, app.getId());
        state.removeState(chatId);

        sendMessage(chatId, i18n.get(lang, "media_album_note"));
        sendPrioritySelection(chatId, lang, app.getId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MEDIA INFO EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════
    private MediaInfo extractMediaInfo(Message message) {
        MediaInfo info = new MediaInfo();
        if (message.hasAudio()) {
            Audio a = message.getAudio();
            info.fileId = a.getFileId(); info.type = "audio"; info.typeLabel = "Audio";
            info.fileName = a.getFileName() != null ? a.getFileName() : "audio.mp3";
            info.fileSize = a.getFileSize() != null ? a.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "Davomiyligi: " + formatDuration(a.getDuration())
                    + (a.getTitle()     != null ? " | " + a.getTitle()     : "")
                    + (a.getPerformer() != null ? " | " + a.getPerformer() : "");
            return info;
        }
        if (message.hasVoice()) {
            Voice v = message.getVoice();
            info.fileId = v.getFileId(); info.type = "voice"; info.typeLabel = "Ovozli xabar";
            info.fileName = "voice.ogg";
            info.fileSize = v.getFileSize() != null ? v.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "Davomiyligi: " + formatDuration(v.getDuration());
            return info;
        }
        if (message.hasVideo()) {
            Video v = message.getVideo();
            info.fileId = v.getFileId(); info.type = "video"; info.typeLabel = "Video";
            info.fileName = v.getFileName() != null ? v.getFileName() : "video.mp4";
            info.fileSize = v.getFileSize() != null ? v.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "O'lcham: " + v.getWidth() + "x" + v.getHeight()
                    + " | Davomiylik: " + formatDuration(v.getDuration());
            return info;
        }
        if (message.hasVideoNote()) {
            VideoNote vn = message.getVideoNote();
            info.fileId = vn.getFileId(); info.type = "video_note"; info.typeLabel = "Video xabar";
            info.fileName = "video_note.mp4";
            info.fileSize = vn.getFileSize() != null ? vn.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "Davomiyligi: " + formatDuration(vn.getDuration());
            return info;
        }
        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            PhotoSize photo = photos.get(photos.size() - 1);
            info.fileId = photo.getFileId(); info.type = "photo"; info.typeLabel = "Rasm";
            info.fileName = "photo.jpg";
            info.fileSize = photo.getFileSize() != null ? photo.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "O'lcham: " + photo.getWidth() + "x" + photo.getHeight();
            return info;
        }
        if (message.hasAnimation()) {
            Animation anim = message.getAnimation();
            info.fileId = anim.getFileId(); info.type = "animation"; info.typeLabel = "GIF / Animatsiya";
            info.fileName = anim.getFileName() != null ? anim.getFileName() : "animation.gif";
            info.fileSize = anim.getFileSize() != null ? anim.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "O'lcham: " + anim.getWidth() + "x" + anim.getHeight()
                    + " | Davomiylik: " + formatDuration(anim.getDuration());
            return info;
        }
        if (message.hasDocument()) {
            Document doc = message.getDocument();
            String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
            info.fileId = doc.getFileId(); info.type = "document";
            info.typeLabel = getDocumentTypeLabel(mime, doc.getFileName());
            info.fileName = doc.getFileName() != null ? doc.getFileName() : "file";
            info.fileSize = doc.getFileSize() != null ? doc.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.mimeType = mime; info.extra = "MIME: " + mime;
            return info;
        }
        if (message.hasSticker()) {
            Sticker s = message.getSticker();
            info.fileId = s.getFileId(); info.type = "sticker";
            info.typeLabel = Boolean.TRUE.equals(s.getIsAnimated()) ? "Animatsiyali stiker" : "Stiker";
            info.fileName = "sticker.webp";
            info.fileSize = s.getFileSize() != null ? s.getFileSize().longValue() : 0;
            info.fileSizeStr = formatFileSize(info.fileSize);
            info.extra = "Emoji: " + (s.getEmoji() != null ? s.getEmoji() : "—");
            return info;
        }
        if (message.hasLocation()) {
            Location loc = message.getLocation();
            info.fileId = null; info.type = "location"; info.typeLabel = "Joylashuv";
            info.fileName = "location"; info.fileSize = 0; info.fileSizeStr = "—";
            info.extra = "Kenglik: " + loc.getLatitude() + " | Bo'ylama: " + loc.getLongitude();
            return info;
        }
        if (message.hasContact()) {
            Contact ct = message.getContact();
            info.fileId = null; info.type = "contact"; info.typeLabel = "Kontakt";
            info.fileName = "contact"; info.fileSize = 0; info.fileSizeStr = "—";
            info.extra = ct.getFirstName()
                    + (ct.getLastName() != null ? " " + ct.getLastName() : "")
                    + " | Tel: " + ct.getPhoneNumber();
            return info;
        }
        return null;
    }

    private String getDocumentTypeLabel(String mime, String fileName) {
        if (mime == null) mime = "";
        String fn = fileName != null ? fileName.toLowerCase() : "";
        if (mime.contains("word") || fn.endsWith(".doc") || fn.endsWith(".docx")) return "Word hujjati";
        if (mime.contains("excel") || mime.contains("spreadsheet") || fn.endsWith(".xls") || fn.endsWith(".xlsx")) return "Excel jadval";
        if (mime.contains("powerpoint") || fn.endsWith(".ppt") || fn.endsWith(".pptx")) return "PowerPoint";
        if (mime.contains("pdf") || fn.endsWith(".pdf")) return "PDF hujjat";
        if (mime.contains("zip") || mime.contains("rar") || fn.endsWith(".zip") || fn.endsWith(".rar")) return "Arxiv fayl";
        if (mime.contains("text/plain") || fn.endsWith(".txt")) return "Matn fayli";
        if (mime.contains("csv") || fn.endsWith(".csv")) return "CSV fayl";
        if (mime.startsWith("image/")) return "Rasm fayl";
        if (mime.startsWith("audio/")) return "Audio fayl";
        if (mime.startsWith("video/")) return "Video fayl";
        return "Fayl";
    }

    private static class MediaInfo {
        String fileId, type, typeLabel, fileName, fileSizeStr, mimeType, extra;
        long   fileSize;
    }

    private String buildMediaDescription(MediaInfo media, String caption) {
        StringBuilder sb = new StringBuilder("[").append(media.typeLabel).append("]");
        if (media.fileName != null && !media.fileName.isEmpty())
            sb.append("\nFayl: ").append(media.fileName);
        if (media.fileSizeStr != null && !"—".equals(media.fileSizeStr))
            sb.append(" (").append(media.fileSizeStr).append(")");
        if (media.extra != null && !media.extra.isEmpty())
            sb.append("\n").append(media.extra);
        if (media.fileId != null)
            sb.append("\nfileId=").append(media.fileId);
        if (caption != null && !caption.isBlank())
            sb.append("\nIzoh: ").append(caption);
        return sb.toString();
    }

    private String getMediaConfirmText(String lang, String typeLabel, String fileName, String fileSize) {
        return switch (lang) {
            case "en" -> typeLabel + " received! " + fileName + " (" + fileSize + ")";
            case "ru" -> typeLabel + " получен! " + fileName + " (" + fileSize + ")";
            default   -> typeLabel + " qabul qilindi! " + fileName + " (" + fileSize + ")";
        };
    }

    private String getFileSizeErrorText(String lang, long limitMB) {
        return switch (lang) {
            case "en" -> "File too large! Maximum: " + limitMB + " MB.";
            case "ru" -> "Файл слишком большой! Максимум: " + limitMB + " МБ.";
            default   -> "Fayl juda katta! Maksimal: " + limitMB + " MB.";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SUXBAT (LIVE CHAT)
    // ═══════════════════════════════════════════════════════════════════════
    private void handleUserChatMessage(String userChatId, Message message) throws TelegramApiException {
        Optional<Long> appIdOpt = state.getChatAppId(userChatId);
        if (appIdOpt.isEmpty()) {
            state.removeState(userChatId);
            sendMessage(userChatId, i18n.get(state.getLang(userChatId), "restart"));
            return;
        }
        Long appId = appIdOpt.get();
        for (String adminId : adminChatIds) {
            sendTyping(adminId);
            if (message.hasText()) {
                sendMessage(adminId, "Foydalanuvchi (#" + appId + "):\n" + message.getText());
                saveChatMessage(appId, "USER", userChatId, message.getText());
            } else {
                String caption = "Foydalanuvchi (#" + appId + ")"
                        + (message.getCaption() != null ? ":\n" + message.getCaption() : "");
                forwardMediaToChat(adminId, message, caption);
                saveChatMessage(appId, "USER", userChatId, buildChatMediaLog(message));
            }
        }
    }

    private void handleAdminChatMessage(String adminId, String userChatId, Message message)
            throws TelegramApiException {
        Long appId = state.getChatAppId(userChatId).orElse(0L);
        sendTyping(userChatId);
        if (message.hasText()) {
            String text = message.getText().trim();
            sendMessage(userChatId, "Operator:\n" + text);
            saveChatMessage(appId, "ADMIN", adminId, text);
            notifyOtherAdmins(adminId, "Admin (#" + appId + "):\n" + text);
        } else {
            String caption = message.getCaption() != null ? message.getCaption() : "";
            forwardMediaToChat(userChatId, message,
                    "Operator" + (caption.isEmpty() ? "" : ":\n" + caption));
            saveChatMessage(appId, "ADMIN", adminId, buildChatMediaLog(message));
        }
    }

    private void forwardMediaToChat(String targetChatId, Message source, String caption)
            throws TelegramApiException {
        if (source.hasPhoto()) {
            List<PhotoSize> photos = source.getPhoto();
            SendPhoto sp = new SendPhoto();
            sp.setChatId(targetChatId);
            sp.setPhoto(new InputFile(photos.get(photos.size()-1).getFileId()));
            if (!caption.isBlank()) sp.setCaption(caption);
            execute(sp);
        } else if (source.hasAudio()) {
            SendAudio sa = new SendAudio(); sa.setChatId(targetChatId);
            sa.setAudio(new InputFile(source.getAudio().getFileId()));
            if (!caption.isBlank()) sa.setCaption(caption); execute(sa);
        } else if (source.hasVoice()) {
            SendVoice sv = new SendVoice(); sv.setChatId(targetChatId);
            sv.setVoice(new InputFile(source.getVoice().getFileId()));
            if (!caption.isBlank()) sv.setCaption(caption); execute(sv);
        } else if (source.hasVideo()) {
            SendVideo svid = new SendVideo(); svid.setChatId(targetChatId);
            svid.setVideo(new InputFile(source.getVideo().getFileId()));
            if (!caption.isBlank()) svid.setCaption(caption); execute(svid);
        } else if (source.hasVideoNote()) {
            SendVideoNote svn = new SendVideoNote(); svn.setChatId(targetChatId);
            svn.setVideoNote(new InputFile(source.getVideoNote().getFileId()));
            execute(svn); if (!caption.isBlank()) sendMessage(targetChatId, caption);
        } else if (source.hasAnimation()) {
            SendAnimation sanim = new SendAnimation(); sanim.setChatId(targetChatId);
            sanim.setAnimation(new InputFile(source.getAnimation().getFileId()));
            if (!caption.isBlank()) sanim.setCaption(caption); execute(sanim);
        } else if (source.hasDocument()) {
            SendDocument sd = new SendDocument(); sd.setChatId(targetChatId);
            sd.setDocument(new InputFile(source.getDocument().getFileId()));
            if (!caption.isBlank()) sd.setCaption(caption); execute(sd);
        } else if (source.hasSticker()) {
            SendSticker ss = new SendSticker(); ss.setChatId(targetChatId);
            ss.setSticker(new InputFile(source.getSticker().getFileId()));
            execute(ss); if (!caption.isBlank()) sendMessage(targetChatId, caption);
        } else if (source.hasLocation()) {
            Location loc = source.getLocation();
            SendLocation sl = new SendLocation(); sl.setChatId(targetChatId);
            sl.setLatitude(loc.getLatitude()); sl.setLongitude(loc.getLongitude()); execute(sl);
            sendMessage(targetChatId, caption);
        } else if (source.hasContact()) {
            Contact ct = source.getContact();
            SendContact sc = new SendContact(); sc.setChatId(targetChatId);
            sc.setPhoneNumber(ct.getPhoneNumber()); sc.setFirstName(ct.getFirstName());
            if (ct.getLastName() != null) sc.setLastName(ct.getLastName()); execute(sc);
            if (!caption.isBlank()) sendMessage(targetChatId, caption);
        } else {
            sendMessage(targetChatId, caption.isBlank() ? "Media xabar" : caption);
        }
    }

    private String buildChatMediaLog(Message msg) {
        if (msg.hasPhoto())     return "[Rasm yuborildi]";
        if (msg.hasAudio())     return "[Audio: " + (msg.getAudio().getFileName() != null ? msg.getAudio().getFileName() : "audio") + "]";
        if (msg.hasVoice())     return "[Ovozli xabar, " + formatDuration(msg.getVoice().getDuration()) + "]";
        if (msg.hasVideo())     return "[Video: " + (msg.getVideo().getFileName() != null ? msg.getVideo().getFileName() : "video") + "]";
        if (msg.hasVideoNote()) return "[Video xabar, " + formatDuration(msg.getVideoNote().getDuration()) + "]";
        if (msg.hasAnimation()) return "[GIF yuborildi]";
        if (msg.hasDocument())  return "[Fayl: " + (msg.getDocument().getFileName() != null ? msg.getDocument().getFileName() : "fayl") + "]";
        if (msg.hasSticker())   return "[Stiker: " + (msg.getSticker().getEmoji() != null ? msg.getSticker().getEmoji() : "") + "]";
        if (msg.hasLocation())  return "[Joylashuv: " + msg.getLocation().getLatitude() + ", " + msg.getLocation().getLongitude() + "]";
        if (msg.hasContact())   return "[Kontakt: " + msg.getContact().getFirstName() + " " + msg.getContact().getPhoneNumber() + "]";
        return "[Noma'lum media]";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ADMIN XABARLARI
    // ═══════════════════════════════════════════════════════════════════════
    private void handleAdminMessage(Message message, String chatId) throws TelegramApiException {
        // ─── Faol suxbat ──────────────────────────────────────────────────
        Optional<String> targetUser = state.getChatTarget(chatId);
        if (targetUser.isPresent()) {
            if (message.hasText() && "/endchat".equalsIgnoreCase(message.getText().trim())) {
                endChatSession(chatId, targetUser.get(), true);
            } else {
                handleAdminChatMessage(chatId, targetUser.get(), message);
            }
            return;
        }

        // ─── Admin javob yozmoqda ─────────────────────────────────────────
        if (state.hasState(chatId, BotState.ADMIN_REPLY) && message.hasText()) {
            state.getAdminReplyTarget(chatId).ifPresent(appId -> {
                handleAdminReplyToApp(appId, message.getText().trim(), chatId);
                state.clearAdminReplyTarget(chatId);
                state.removeState(chatId);
            });
            return;
        }

        // ─── [YANGI] Admin muddatni qo'lda kiritmoqda ─────────────────────
        if (state.hasState(chatId, BotState.DEADLINE) && message.hasText()) {
            Long appId = pendingDeadlineInput.remove(chatId);
            if (appId != null) {
                handleCustomDeadlineInput(chatId, appId, message.getText().trim());
                state.removeState(chatId);
            }
            return;
        }

        // ─── Broadcast ────────────────────────────────────────────────────
        if (state.hasState(chatId, BotState.BROADCAST) && message.hasText()) {
            handleBroadcastSend(chatId, message.getText().trim());
            state.removeState(chatId);
            return;
        }

        if (!message.hasText()) return;
        String text = message.getText().trim();

        if ("/word".equalsIgnoreCase(text) || "/export".equalsIgnoreCase(text)) {
            handleWordExportAll(chatId);
        } else if (text.toLowerCase().startsWith("/list")) {
            int n = 5;
            String[] parts = text.split("\\s+");
            if (parts.length > 1) {
                try { n = Math.min(Integer.parseInt(parts[1]), 20); }
                catch (NumberFormatException ignored) {}
            }
            handleAdminList(chatId, n);
        } else if ("/stats".equalsIgnoreCase(text)) {
            handleAdminStats(chatId);
        } else if (text.toLowerCase().startsWith("/close ")) {
            String[] parts = text.split("\\s+");
            if (parts.length > 1) {
                try { handleAdminClose(chatId, Long.parseLong(parts[1])); }
                catch (NumberFormatException e) { sendMessage(chatId, "Format: /close 42"); }
            } else {
                sendMessage(chatId, "Format: /close [murojaat_id]");
            }
        } else if (text.toLowerCase().startsWith("/setdeadline")) {
            // /setdeadline ID KUN — masalan: /setdeadline 5 7  → murojaat #5 ga 7 kunlik muddat
            handleSetDeadlineCommand(chatId, text);
        } else if ("/broadcast".equalsIgnoreCase(text)) {
            state.setState(chatId, BotState.BROADCAST);
            sendMessage(chatId, "Barcha foydalanuvchilarga xabarni yozing:\n(Bekor qilish: /cancel)");
        } else if ("/cancel".equalsIgnoreCase(text)) {
            if (state.getState(chatId).isPresent()) {
                state.removeState(chatId);
                pendingDeadlineInput.remove(chatId);
                sendMessage(chatId, "Bekor qilindi.");
            }
        } else if ("/help".equalsIgnoreCase(text)) {
            sendMessage(chatId, """
                    Admin buyruqlari:
                    /list [N]            — Oxirgi N ta murojaat (default: 5)
                    /stats               — Statistika
                    /close ID            — Murojaatni yopish
                    /setdeadline ID KUN  — Muddatni o'zgartirish (masalan: /setdeadline 5 7)
                    /broadcast           — Barcha foydalanuvchilarga xabar
                    /word                — Barchani Word ga export
                    /endchat             — Suxbatni yakunlash
                    /cancel              — Joriy jarayonni bekor qilish
                    """);
        }
    }

    // ─── Admin: /list ──────────────────────────────────────────────────────
    private void handleAdminList(String chatId, int n) {
        try {
            var apps = repository.findAll(
                    PageRequest.of(0, n, Sort.by("submissionTime").descending())
            ).getContent();
            if (apps.isEmpty()) { sendMessage(chatId, "Murojaatlar yo'q."); return; }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
            StringBuilder sb = new StringBuilder("Oxirgi " + apps.size() + " ta murojaat:\n\n");
            for (Application app : apps) {
                sb.append(getPriorityTag(app.getPriority()))
                        .append("#").append(app.getId()).append(" | ")
                        .append(getStatusText("uz", app.getStatus())).append("\n")
                        .append(app.getApplicantName() != null ? app.getApplicantName() : "Anonim")
                        .append(" | ")
                        .append(app.getSubmissionTime() != null ? app.getSubmissionTime().format(fmt) : "—");
                // Bo'lim va muddat
                if (app.getSection() != null) {
                    sb.append(" | Bo'lim: ").append(getSectionLabel(app.getSection()));
                }
                if (app.getDeadline() != null) {
                    sb.append(" | Muddat: ").append(app.getDeadline().format(fmt));
                }
                sb.append("\n\n");
            }
            reminderService.splitAndSend(chatId, sb.toString());
        } catch (Exception e) {
            log.error("Admin list xatolik: {}", e.getMessage());
            sendMessage(chatId, "Xatolik: " + e.getMessage());
        }
    }

    // ─── Admin: /stats ─────────────────────────────────────────────────────
    private void handleAdminStats(String chatId) {
        try {
            long total    = repository.count();
            long pending  = repository.countByStatus(ApplicationStatus.PENDING);
            long inReview = repository.countByStatus(ApplicationStatus.IN_REVIEW);
            long replied  = repository.countByStatus(ApplicationStatus.REPLIED);
            long closed   = repository.countByStatus(ApplicationStatus.CLOSED);
            long normalSection  = repository.countBySection(ApplicationSection.NORMAL);
            long urgentSection  = repository.countBySection(ApplicationSection.URGENT);
            long noSection      = total - normalSection - urgentSection;
            sendMessage(chatId, ("Statistika\n\n"
                    + "Jami: %d\n"
                    + "Kutmoqda: %d\n"
                    + "Ko'rib chiqilmoqda: %d\n"
                    + "Javob berildi: %d\n"
                    + "Yopildi: %d\n\n"
                    + "Bo'lim:\n"
                    + "  Oddiy: %d\n"
                    + "  Shoshilinch: %d\n"
                    + "  Bo'limsiz: %d")
                    .formatted(total, pending, inReview, replied, closed,
                            normalSection, urgentSection, noSection));
        } catch (Exception e) {
            log.error("Statistika xatolik: {}", e.getMessage());
            sendMessage(chatId, "Statistika xatolik: " + e.getMessage());
        }
    }

    // ─── Admin: /close ─────────────────────────────────────────────────────
    private void handleAdminClose(String chatId, Long appId) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(chatId, "Murojaat topilmadi: #" + appId); return; }
        Application app = opt.orElseThrow();
        app.setStatus(ApplicationStatus.CLOSED);
        repository.save(app);
        sendMessage(chatId, "#" + appId + " yopildi.");
        if (app.getChatId() != null) {
            String lang = app.getLang() != null ? app.getLang() : "uz";
            sendMessage(app.getChatId(), switch (lang) {
                case "en" -> "Your request #" + appId + " has been closed.";
                case "ru" -> "Ваше обращение #" + appId + " закрыто.";
                default   -> "#" + appId + " raqamli murojaatingiz yopildi.";
            });
        }
    }

    // ─── [YANGI] Admin: /setdeadline ID KUN ───────────────────────────────
    private void handleSetDeadlineCommand(String adminId, String text) {
        // /setdeadline 5 7 — murojaat #5 ga 7 kunlik muddat
        String[] parts = text.split("\\s+");
        if (parts.length < 3) {
            sendMessage(adminId, "Format: /setdeadline [murojaat_id] [kun_soni]\nMasalan: /setdeadline 5 7");
            return;
        }
        try {
            Long appId = Long.parseLong(parts[1]);
            int  days  = Integer.parseInt(parts[2]);
            if (days < 1 || days > 365) {
                sendMessage(adminId, "Kun soni 1 dan 365 gacha bo'lishi kerak.");
                return;
            }
            applyDeadline(adminId, appId, days);
        } catch (NumberFormatException e) {
            sendMessage(adminId, "Noto'g'ri format. /setdeadline [id] [kun]");
        }
    }

    /**
     * Admin muddatni qo'lda kiritmoqda (callback dan keyin).
     * Callback: "Muddatni o'zgartirish" tugmasi bosilganda bu rejim yoqiladi.
     */
    private void handleCustomDeadlineInput(String adminId, Long appId, String text) {
        try {
            int days = Integer.parseInt(text.trim());
            if (days < 1 || days > 365) {
                sendMessage(adminId, "Kun soni 1 dan 365 gacha bo'lishi kerak.");
                return;
            }
            applyDeadline(adminId, appId, days);
        } catch (NumberFormatException e) {
            sendMessage(adminId, "Faqat raqam kiriting. Masalan: 7");
        }
    }

    /** Muddatni bazaga yozish va bildirishnoma yuborish */
    private void applyDeadline(String adminId, Long appId, int days) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: #" + appId); return; }
        Application app = opt.orElseThrow();
        LocalDateTime deadline = LocalDateTime.now().plusDays(days);
        app.setDeadline(deadline);
        app.setDeadlineNotified(false);   // ✅ qo'shilsin
        repository.save(app);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        sendMessage(adminId, "✅ #" + appId + " muddati belgilandi: " + deadline.format(fmt)
                + " (" + days + " kun)");

        // Foydalanuvchiga xabar
        if (app.getChatId() != null) {
            String lang = app.getLang() != null ? app.getLang() : "uz";
            String sectionName = app.getSection() != null ? getSectionLabel(app.getSection()) : "—";
            sendMessage(app.getChatId(), buildDeadlineUserNotification(lang, appId, sectionName, deadline));
        }
    }

    // ─── Broadcast ────────────────────────────────────────────────────────
    private void handleBroadcastSend(String adminId, String text) {
        if ("/cancel".equalsIgnoreCase(text)) {
            sendMessage(adminId, "Broadcast bekor qilindi.");
            return;
        }
        List<String> uniqueChats = repository.findDistinctChatIds();
        int sent = 0, failed = 0;
        for (String chatId : uniqueChats) {
            try {
                reminderService.splitAndSend(chatId, text);
                sent++;
                if (sent % 25 == 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) { failed++; }
        }
        sendMessage(adminId, "Broadcast: " + sent + " ta yuborildi, " + failed + " ta yuborilmadi.");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CALLBACK QUERY
    // ═══════════════════════════════════════════════════════════════════════
    private void handleCallbackQuery(Update update) throws TelegramApiException {
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        String data   = update.getCallbackQuery().getData();

        if (CB_ADD_MORE.equals(data)) {
            state.setState(chatId, BotState.ADDITIONAL);
            sendMessage(chatId, i18n.get(state.getLang(chatId), "ask_additional"));
        } else if (CB_MY_APPS.equals(data)) {
            sendMyApplications(chatId);
        } else if (CB_EXPORT_ALL.equals(data)) {
            handleWordExportAll(chatId);
        } else if (data.startsWith(CB_EXPORT_ONE)) {
            parseId(data, CB_EXPORT_ONE).ifPresent(id -> handleWordExportOne(chatId, id));
        } else if (data.startsWith(CB_REPLY)) {
            parseId(data, CB_REPLY).ifPresent(appId -> {
                state.setAdminReplyTarget(chatId, appId);
                state.setState(chatId, BotState.ADMIN_REPLY);
                sendMessage(chatId, "#" + appId + " ga javob matnini yozing:");
            });
        } else if (data.startsWith(CB_CHECK_STATUS)) {
            parseId(data, CB_CHECK_STATUS).ifPresent(id -> showApplicationStatus(chatId, id));
        } else if (data.startsWith(CB_VIEWED)) {
            parseId(data, CB_VIEWED).ifPresent(id -> handleViewed(id, chatId));
        } else if (data.startsWith(CB_PRIORITY_URG)) {
            parseId(data, CB_PRIORITY_URG).ifPresent(id -> setPriority(chatId, id, Priority.URGENT));
        } else if (data.startsWith(CB_PRIORITY_NRM)) {
            parseId(data, CB_PRIORITY_NRM).ifPresent(id -> setPriority(chatId, id, Priority.NORMAL));

            // ─── [YANGI] Bo'lim tanlash ────────────────────────────────────────
        } else if (data.startsWith(CB_SECTION_NRM)) {
            parseId(data, CB_SECTION_NRM).ifPresent(id -> assignSection(chatId, id, ApplicationSection.NORMAL));
        } else if (data.startsWith(CB_SECTION_URG)) {
            parseId(data, CB_SECTION_URG).ifPresent(id -> assignSection(chatId, id, ApplicationSection.URGENT));

        } else if (data.startsWith("custom_deadline_")) {
            // "custom_deadline_42" — admin o'zi muddat kiritmahdi
            parseId(data, "custom_deadline_").ifPresent(appId -> {
                pendingDeadlineInput.put(chatId, appId);
                state.setState(chatId, BotState.DEADLINE);
                sendMessage(chatId, "#" + appId + " uchun necha kun muddat belgilamoqchisiz? (raqam kiriting)");
            });
        } else if (data.startsWith(CB_START_CHAT)) {
            parseId(data, CB_START_CHAT).ifPresent(appId -> startChatSession(chatId, appId));
        } else if (data.startsWith(CB_END_CHAT)) {
            parseId(data, CB_END_CHAT).ifPresent(appId -> {
                if (isAdmin(chatId)) {
                    state.getChatTarget(chatId).ifPresent(userCid -> endChatSession(chatId, userCid, true));
                } else {
                    endChatSession(null, chatId, false);
                }
            });
        } else if (data.startsWith(CB_FORWARD_FILE)) {
            parseId(data, CB_FORWARD_FILE).ifPresent(appId -> resendFileToAdmin(chatId, appId));
        } else if (data.startsWith(CB_CLOSE_APP)) {
            parseId(data, CB_CLOSE_APP).ifPresent(appId -> handleAdminClose(chatId, appId));
        }
    }

    // ─── [YANGI] Bo'lim belgilash ──────────────────────────────────────────
    /**
     * Admin "Oddiy bo'lim" yoki "Shoshilinch bo'lim" tugmasini bosadi.
     *
     * Logika:
     *   1. section → NORMAL yoki URGENT
     *   2. deadline avtomatik qo'yiladi (NORMAL→+10kun, URGENT→+5kun)
     *   3. Foydalanuvchiga xabar yuboriladi
     *   4. Admin muddat o'zgartirish imkoniga ega (tugma)
     */
    private void assignSection(String adminId, Long appId, ApplicationSection section) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: #" + appId); return; }

        Application app = opt.orElseThrow();
        app.setSection(section);

        int days = (section == ApplicationSection.URGENT) ? DEADLINE_URGENT_DAYS : DEADLINE_NORMAL_DAYS;
        LocalDateTime deadline = LocalDateTime.now().plusDays(days);
        app.setDeadline(deadline);
        app.setDeadlineNotified(false);   // ✅ bu yerga ham qo'shilsin
        repository.save(app);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        String sectionLabel = getSectionLabel(section);

        // Admin ga tasdiqlash
        sendMessage(adminId, String.format(
                "✅ Murojaat #%d\n"
                        + "Bo'lim: %s\n"
                        + "Muddat: %s (%d kun)\n\n"
                        + "Muddatni o'zgartirish uchun: /setdeadline %d [kun]",
                appId, sectionLabel, deadline.format(fmt), days, appId));

        // Admin klaviaturasi: muddat o'zgartirish tugmasi
        try {
            SendMessage msg = new SendMessage(adminId, "Muddatni o'zgartirishni xohlaysizmi?");
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            InlineKeyboardButton changeBtn = new InlineKeyboardButton();
            changeBtn.setText("Muddatni o'zgartirish (#" + appId + ")");
            changeBtn.setCallbackData("custom_deadline_" + appId);
            markup.setKeyboard(List.of(List.of(changeBtn)));
            msg.setReplyMarkup(markup);
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("assignSection muddat tugma yuborishda xato: {}", e.getMessage());
        }

        // Foydalanuvchiga xabar
        if (app.getChatId() != null) {
            String lang = app.getLang() != null ? app.getLang() : "uz";
            sendMessage(app.getChatId(), buildDeadlineUserNotification(lang, appId, sectionLabel, deadline));
        }

        // Boshqa adminlarga xabar
        notifyOtherAdmins(adminId, "Murojaat #" + appId + " → " + sectionLabel + " bo'limiga o'tkazildi. Muddat: " + deadline.format(fmt));
    }

    /** Foydalanuvchiga bo'lim va muddat haqida xabar matni */
    private String buildDeadlineUserNotification(String lang, Long appId,
                                                 String sectionLabel, LocalDateTime deadline) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        return switch (lang) {
            case "en" -> String.format(
                    "Your request #%d has been assigned to the '%s' section.\n"
                            + "Expected response deadline: %s",
                    appId, sectionLabel, deadline.format(fmt));
            case "ru" -> String.format(
                    "Ваше обращение #%d направлено в раздел '%s'.\n"
                            + "Ожидаемый срок ответа: %s",
                    appId, sectionLabel, deadline.format(fmt));
            default -> String.format(
                    "#%d raqamli murojaatingiz '%s' bo'limiga yo'naltirildi.\n"
                            + "Javob berish muddati: %s",
                    appId, sectionLabel, deadline.format(fmt));
        };
    }

    // ─── Bo'lim label ─────────────────────────────────────────────────────
    private String getSectionLabel(ApplicationSection section) {
        return switch (section) {
            case NORMAL -> "Oddiy bo'lim";
            case URGENT -> "Shoshilinch bo'lim";
        };
    }

    // ─── Admin faylni qayta olishi ────────────────────────────────────────
    private void resendFileToAdmin(String adminId, Long appId) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: #" + appId); return; }
        Application app = opt.orElseThrow();
        if (app.getFileId() == null || app.getFileId().isBlank()) {
            sendMessage(adminId, "Bu murojaatda fayl mavjud emas."); return;
        }
        try {
            sendFileByTypeAndId(adminId, app.getFileId(), app.getFileType(),
                    "Murojaat #" + appId + " fayli\nTur: " + (app.getFileType() != null ? app.getFileType() : "fayl"));
        } catch (Exception e) {
            log.error("Faylni yuborishda xatolik [appId={}]: {}", appId, e.getMessage());
            sendMessage(adminId, "Faylni yuborishda xatolik: " + e.getMessage());
        }
    }

    private void sendFileByTypeAndId(String chatId, String fileId, String fileType, String caption)
            throws TelegramApiException {
        if (fileType == null) fileType = "document";
        switch (fileType.toLowerCase()) {
            case "photo"     -> { SendPhoto sp = new SendPhoto(); sp.setChatId(chatId); sp.setPhoto(new InputFile(fileId)); if (caption != null) sp.setCaption(caption); execute(sp); }
            case "audio"     -> { SendAudio sa = new SendAudio(); sa.setChatId(chatId); sa.setAudio(new InputFile(fileId)); if (caption != null) sa.setCaption(caption); execute(sa); }
            case "voice"     -> { SendVoice sv = new SendVoice(); sv.setChatId(chatId); sv.setVoice(new InputFile(fileId)); if (caption != null) sv.setCaption(caption); execute(sv); }
            case "video"     -> { SendVideo svid = new SendVideo(); svid.setChatId(chatId); svid.setVideo(new InputFile(fileId)); if (caption != null) svid.setCaption(caption); execute(svid); }
            case "video_note"-> { SendVideoNote svn = new SendVideoNote(); svn.setChatId(chatId); svn.setVideoNote(new InputFile(fileId)); execute(svn); if (caption != null) sendMessage(chatId, caption); }
            case "animation" -> { SendAnimation sanim = new SendAnimation(); sanim.setChatId(chatId); sanim.setAnimation(new InputFile(fileId)); if (caption != null) sanim.setCaption(caption); execute(sanim); }
            case "sticker"   -> { SendSticker ss = new SendSticker(); ss.setChatId(chatId); ss.setSticker(new InputFile(fileId)); execute(ss); if (caption != null) sendMessage(chatId, caption); }
            default          -> { SendDocument sd = new SendDocument(); sd.setChatId(chatId); sd.setDocument(new InputFile(fileId)); if (caption != null) sd.setCaption(caption); execute(sd); }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SUXBAT SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════
    private void startChatSession(String adminId, Long appId) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: #" + appId); return; }
        Application app = opt.orElseThrow();
        String userChatId = app.getChatId();
        if (userChatId == null) { sendMessage(adminId, "Foydalanuvchi chat ID topilmadi"); return; }

        state.startChat(adminId, userChatId, appId);
        state.setState(userChatId, BotState.CHAT);
        chatLastActivity.put(userChatId, System.currentTimeMillis());

        String lang = app.getLang() != null ? app.getLang() : "uz";
        String userMsg = switch (lang) {
            case "en" -> "Operator joined for request #" + appId + ". Press button to end.";
            case "ru" -> "Оператор подключился по обращению #" + appId + ". Нажмите кнопку.";
            default   -> "Operator #" + appId + " murojaat bo'yicha suxbatga qo'shildi. Yakunlash uchun pastdagi tugmani bosing.";
        };
        sendMessageWithEndChatButton(userChatId, userMsg, lang, appId);
        sendMessage(adminId, "Suxbat boshlandi! #" + appId + "\nTugatish: /endchat");
        sendAdminEndChatButton(adminId, appId);
        saveChatMessage(appId, "SYSTEM", adminId, "--- Suxbat boshlandi ---");
    }

    private void endChatSession(String adminId, String userChatId, boolean byAdmin) {
        Optional<Long> appId = state.getChatAppId(userChatId);
        state.endChat(adminId, userChatId);
        state.removeState(userChatId);
        chatLastActivity.remove(userChatId);

        appId.ifPresent(id -> saveChatMessage(id, "SYSTEM", "system",
                byAdmin ? "--- Admin suxbatni yakunladi ---" : "--- Foydalanuvchi suxbatni yakunladi ---"));

        String lang = state.getLang(userChatId);
        sendMessage(userChatId, switch (lang) {
            case "en" -> "Chat ended. Thank you!";
            case "ru" -> "Чат завершён. Спасибо!";
            default   -> "Suxbat yakunlandi. Rahmat!";
        });
        if (adminId != null)
            sendMessage(adminId, "Suxbat yakunlandi." + appId.map(id -> " (#" + id + ")").orElse(""));
        String notify = "Suxbat yakunlandi" + appId.map(id -> " (#" + id + ")").orElse("")
                + (byAdmin ? " — Admin tomonidan" : " — Foydalanuvchi tomonidan");
        for (String aid : adminChatIds) if (!aid.equals(adminId)) sendMessage(aid, notify);
    }

    // ─── Chat timeout ─────────────────────────────────────────────────────
    private void checkChatTimeouts() {
        long now = System.currentTimeMillis();
        List<Map.Entry<String, Long>> snapshot = new ArrayList<>(chatLastActivity.entrySet());
        for (Map.Entry<String, Long> entry : snapshot) {
            String userChatId = entry.getKey();
            Long   lastActive  = entry.getValue();
            try {
                if (lastActive != null
                        && now - lastActive > CHAT_TIMEOUT_MS
                        && state.isUserInChat(userChatId)) {
                    sendMessage(userChatId, i18n.get(state.getLang(userChatId), "chat_timeout"));
                    endChatSession(null, userChatId, false);
                    chatLastActivity.remove(userChatId);
                }
            } catch (Exception e) {
                log.error("Chat timeout xato [chatId={}]: {}", userChatId, e.getMessage());
            }
        }
    }

    // ─── Typing ───────────────────────────────────────────────────────────
    private void sendTyping(String chatId) {
        try {
            SendChatAction action = new SendChatAction();
            action.setChatId(chatId);
            action.setAction(ActionType.TYPING);
            execute(action);
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TIL TANLASH
    // ═══════════════════════════════════════════════════════════════════════
    private void sendLanguageSelection(String chatId) throws TelegramApiException {
        SendMessage msg = new SendMessage(chatId, i18n.get("uz", "welcome"));
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(true);
        KeyboardRow r1 = new KeyboardRow();
        r1.add(new KeyboardButton("O'zbekcha"));
        r1.add(new KeyboardButton("English"));
        KeyboardRow r2 = new KeyboardRow();
        r2.add(new KeyboardButton("Русский"));
        markup.setKeyboard(List.of(r1, r2));
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    private void handleLanguageSelection(String chatId, String text) throws TelegramApiException {
        String lower = text.toLowerCase();
        String lang;
        if      (lower.contains("o'zbek") || lower.contains("uzbek")) lang = "uz";
        else if (lower.contains("english"))                            lang = "en";
        else if (lower.contains("рус"))                                lang = "ru";
        else {
            sendMessage(chatId, "Iltimos, pastdagi tugmalardan birini tanlang.");
            sendLanguageSelection(chatId);
            return;
        }
        state.setLang(chatId, lang);
        // [YANGI] Til → Ism so'rash
        state.setState(chatId, BotState.FULL_NAME);
        SendMessage msg = new SendMessage(chatId, i18n.get(lang, "ask_fullname"));
        msg.setReplyMarkup(new ReplyKeyboardRemove(true));
        execute(msg);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MUROJAAT OQIMI
    // ═══════════════════════════════════════════════════════════════════════
    private void handleDescription(String chatId, String text) throws TelegramApiException {
        String lang = state.getLang(chatId);
        if (text.isBlank()) { sendMessage(chatId, i18n.get(lang, "ask_desc")); return; }
        Application app = saveApplication(chatId, lang, text, null, null, null);
        state.setLastAppId(chatId, app.getId());
        state.removeState(chatId);
        sendPrioritySelection(chatId, lang, app.getId());
    }

    private void handleAdditional(String chatId, String text) throws TelegramApiException {
        String lang     = state.getLang(chatId);
        if (text.isBlank()) { sendMessage(chatId, i18n.get(lang, "ask_additional")); return; }
        Long   parentId = state.getLastAppId(chatId).orElse(null);
        Application app = saveApplication(chatId, lang, "[Qo'shimcha] " + text, parentId, null, null);
        state.setLastAppId(chatId, app.getId());
        state.removeState(chatId);
        String timeStr = app.getSubmissionTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        sendWithMainButtons(chatId,
                i18n.get(lang, "additional_saved") + "\n\n" + buildConfirmText(lang, app.getId(), timeStr, app.getPriority()),
                lang, app.getId());
        sendAdminNotification(app);
    }

    private void sendPrioritySelection(String chatId, String lang, Long appId) throws TelegramApiException {
        String question = switch (lang) {
            case "en" -> "Is your request urgent?";
            case "ru" -> "Ваше обращение срочное?";
            default   -> "Murojaatingiz shoshilinchmi?";
        };
        SendMessage msg = new SendMessage(chatId, question);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        InlineKeyboardButton urgBtn = new InlineKeyboardButton();
        urgBtn.setText(switch (lang) { case "en" -> "Urgent"; case "ru" -> "Срочно"; default -> "Shoshilinch"; });
        urgBtn.setCallbackData(CB_PRIORITY_URG + appId);
        InlineKeyboardButton nrmBtn = new InlineKeyboardButton();
        nrmBtn.setText(switch (lang) { case "en" -> "Normal"; case "ru" -> "Обычное"; default -> "Oddiy"; });
        nrmBtn.setCallbackData(CB_PRIORITY_NRM + appId);
        markup.setKeyboard(List.of(List.of(urgBtn, nrmBtn)));
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    private void setPriority(String chatId, Long appId, Priority priority) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(chatId, "Murojaat topilmadi"); return; }
        Application app = opt.orElseThrow();
        app.setPriority(priority);
        repository.save(app);
        String lang    = state.getLang(chatId);
        String timeStr = app.getSubmissionTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        try {
            sendWithMainButtons(chatId,
                    i18n.get(lang, "thanks") + "\n\n" + buildConfirmText(lang, app.getId(), timeStr, priority),
                    lang, app.getId());
        } catch (TelegramApiException e) {
            log.error("sendWithMainButtons xato: {}", e.getMessage());
            sendMessage(chatId, i18n.get(lang, "error_user"));
        }
        sendAdminNotification(app);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MENING MUROJAATLARIM
    // ═══════════════════════════════════════════════════════════════════════
    private void sendMyApplications(String chatId) throws TelegramApiException {
        List<Application> apps = repository.findByChatIdOrderBySubmissionTimeDesc(chatId);
        String lang = state.getLang(chatId);
        if (apps.isEmpty()) { sendMessage(chatId, i18n.get(lang, "no_apps")); return; }

        sendMessage(chatId, switch (lang) {
            case "en" -> "Your requests (" + apps.size() + " total):";
            case "ru" -> "Ваши обращения (" + apps.size() + " всего):";
            default   -> "Mening murojaatlarim (jami: " + apps.size() + " ta):";
        });

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (Application app : apps) {
            String desc = app.getDescription();
            if (desc != null && desc.length() > 150) desc = desc.substring(0, 147) + "...";
            String text = String.format("%s#%d | %s\n%s%s\n\n%s",
                    getPriorityTag(app.getPriority()), app.getId(),
                    getStatusText(lang, app.getStatus()),
                    app.getSubmissionTime() != null ? app.getSubmissionTime().format(fmt) : "—",
                    app.getFileType() != null ? "\n" + getMediaTag(app.getFileType()) : "",
                    desc != null ? desc : "—");

            SendMessage msg = new SendMessage(chatId, text);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(i18n.get(lang, "btn_check_status"));
            btn.setCallbackData(CB_CHECK_STATUS + app.getId());
            markup.setKeyboard(List.of(List.of(btn)));
            msg.setReplyMarkup(markup);
            execute(msg);
        }
    }

    private String getMediaTag(String fileType) {
        if (fileType == null) return "";
        return switch (fileType.toLowerCase()) {
            case "photo"      -> "Rasm bor";
            case "audio"      -> "Audio bor";
            case "voice"      -> "Ovozli xabar bor";
            case "video"      -> "Video bor";
            case "video_note" -> "Video xabar bor";
            case "animation"  -> "GIF bor";
            case "document"   -> "Fayl bor";
            case "sticker"    -> "Stiker bor";
            case "location"   -> "Joylashuv bor";
            case "contact"    -> "Kontakt bor";
            default           -> "Media bor";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STATUS
    // ═══════════════════════════════════════════════════════════════════════
    private void showApplicationStatus(String chatId, Long appId) {
        var opt = repository.findById(appId);
        if (opt.isEmpty()) { sendMessage(chatId, "Murojaat topilmadi: " + appId); return; }
        Application app  = opt.orElseThrow();
        String      lang = state.getLang(chatId);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        String viewedText = app.getViewedAt() != null
                ? "Ko'rilgan: " + app.getViewedAt().format(fmt) : "Hali ko'rilmagan";
        String replyText = (app.getAdminReply() != null && !app.getAdminReply().isBlank())
                ? "\n\n" + i18n.get(lang, "admin_reply") + ":\n" + app.getAdminReply()
                : "\n\n" + i18n.get(lang, "no_reply");
        String mediaInfo = app.getFileType() != null ? "\n" + getMediaTag(app.getFileType()) : "";

        // [YANGI] Bo'lim va muddat ma'lumoti
        String sectionInfo = "";
        if (app.getSection() != null) {
            sectionInfo += "\nBo'lim: " + getSectionLabel(app.getSection());
        }
        if (app.getDeadline() != null) {
            sectionInfo += "\nMuddat: " + app.getDeadline().format(fmt);
        }

        sendMessage(chatId, String.format("%s#%d\n\n%s: %s\n%s\n%s%s%s%s",
                getPriorityTag(app.getPriority()), app.getId(),
                i18n.get(lang, "status_label"), getStatusText(lang, app.getStatus()),
                viewedText,
                app.getSubmissionTime() != null ? app.getSubmissionTime().format(fmt) : "—",
                mediaInfo, sectionInfo, replyText));
    }

    // ─── Ko'rdim ──────────────────────────────────────────────────────────
    private void handleViewed(Long id, String adminId) {
        var opt = repository.findById(id);
        if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: " + id); return; }
        Application app = opt.orElseThrow();
        boolean changed = false;
        if (app.getStatus() == ApplicationStatus.PENDING) { app.setStatus(ApplicationStatus.IN_REVIEW); changed = true; }
        if (app.getViewedAt() == null) { app.setViewedAt(LocalDateTime.now()); changed = true; }
        if (changed) repository.save(app);
        sendMessage(adminId, "#" + id + " ko'rib chiqilmoqda deb belgilandi.");
        if (app.getChatId() != null) {
            sendMessage(app.getChatId(), switch (app.getLang() != null ? app.getLang() : "uz") {
                case "en" -> "Your application #" + id + " is now under review!";
                case "ru" -> "Ваше обращение #" + id + " сейчас на рассмотрении!";
                default   -> "#" + id + " raqamli murojaatingiz ko'rib chiqilmoqda!";
            });
        }
    }

    // ─── Admin javob berish ───────────────────────────────────────────────
    private void handleAdminReplyToApp(Long id, String replyText, String adminId) {
        try {
            var opt = repository.findById(id);
            if (opt.isEmpty()) { sendMessage(adminId, "Murojaat topilmadi: " + id); return; }
            Application app = opt.orElseThrow();
            app.setAdminReply(replyText);
            app.setStatus(ApplicationStatus.REPLIED);
            app.setRepliedAt(LocalDateTime.now());
            repository.save(app);
            reminderService.cancelReminder(id);
            if (app.getChatId() != null) {
                String lang = app.getLang() != null ? app.getLang() : "uz";
                execute(new SendMessage(app.getChatId(), switch (lang) {
                    case "en" -> "Reply to request #" + id + ":\n\n" + replyText;
                    case "ru" -> "Ответ на обращение #" + id + ":\n\n" + replyText;
                    default   -> "#" + id + " raqamli murojaatingizga javob:\n\n" + replyText;
                }));
            }
            sendMessage(adminId, "#" + id + " ga javob muvaffaqiyatli yuborildi.");
            notifyOtherAdmins(adminId, "Admin murojaat #" + id + " ga javob berdi.");
        } catch (Exception e) {
            log.error("Admin reply xato [appId={}]: {}", id, e.getMessage());
            sendMessage(adminId, "Xatolik: " + e.getMessage());
        }
    }

    // ─── Admin notification ───────────────────────────────────────────────
    /**
     * [YANGI] Yangi murojaat kelganda admin klaviaturasida bo'lim tanlash tugmalari qo'shildi.
     * Admin avval bo'limni tanlaydi → muddat avtomatik belgilanadi.
     */
    private void sendAdminNotification(Application app) {
        try {
            boolean isUrgent = Priority.URGENT == app.getPriority();
            String header = isUrgent ? "SHOSHILINCH MUROJAAT!" : "Yangi murojaat";
            String mediaInfo = app.getFileType() != null && !app.getFileType().isBlank()
                    ? "\nMedia: " + getMediaTag(app.getFileType()) : "";
            String applicantDisplay = app.getApplicantName() != null && !app.getApplicantName().equals("Anonim")
                    ? "Kim: " + app.getApplicantName() + "\n" : "";
            // [YANGI] Telefon raqami
            String phoneDisplay = app.getPhoneNumber() != null && !app.getPhoneNumber().isBlank()
                    ? "Tel: " + app.getPhoneNumber() + "\n" : "";

            String text = ("%s\n\n%sID: #%d\n%s%sTil: %s\nSana: %s%s\nMazmun:\n%s")
                    .formatted(
                            header,
                            getPriorityTag(app.getPriority()),
                            app.getId(),
                            applicantDisplay,
                            phoneDisplay,
                            app.getLang() != null ? app.getLang().toUpperCase() : "—",
                            app.getSubmissionTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            mediaInfo,
                            app.getDescription()
                    );

            InlineKeyboardMarkup markup = buildAdminKeyboard(app.getId(), app.getFileId() != null);
            for (String adminId : adminChatIds) {
                SendMessage msg = new SendMessage(adminId, text);
                msg.setReplyMarkup(markup);
                execute(msg);
            }
            reminderService.scheduleReminder(app);
        } catch (Exception e) {
            log.error("Admin notification xato: {}", e.getMessage());
        }
    }

    /**
     * [YANGI] Admin klaviaturasiga bo'lim tanlash tugmalari qo'shildi.
     *
     *   [Javob berish]
     *   [Ko'rdim]  [Word]
     *   [Oddiy bo'lim]  [Shoshilinch bo'lim]   ← YANGI qator
     *   [Suxbat boshlash]  [Yopish]
     *   [Word (barchasi)]
     */
    private InlineKeyboardMarkup buildAdminKeyboard(Long appId, boolean hasFile) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // 1. Javob berish
        InlineKeyboardButton replyBtn = new InlineKeyboardButton();
        replyBtn.setText("Javob berish"); replyBtn.setCallbackData(CB_REPLY + appId);
        rows.add(List.of(replyBtn));

        // 2. Ko'rdim + Word
        InlineKeyboardButton viewedBtn = new InlineKeyboardButton();
        viewedBtn.setText("Ko'rdim"); viewedBtn.setCallbackData(CB_VIEWED + appId);
        InlineKeyboardButton exportBtn = new InlineKeyboardButton();
        exportBtn.setText("Word"); exportBtn.setCallbackData(CB_EXPORT_ONE + appId);
        rows.add(List.of(viewedBtn, exportBtn));

        // 3. Fayl (ixtiyoriy)
        if (hasFile) {
            InlineKeyboardButton fileBtn = new InlineKeyboardButton();
            fileBtn.setText("Faylni yuklab olish"); fileBtn.setCallbackData(CB_FORWARD_FILE + appId);
            rows.add(List.of(fileBtn));
        }

        // 4. [YANGI] Bo'lim tanlash: Oddiy | Shoshilinch
        InlineKeyboardButton normalSectBtn = new InlineKeyboardButton();
        normalSectBtn.setText("📋 Oddiy bo'lim");
        normalSectBtn.setCallbackData(CB_SECTION_NRM + appId);
        InlineKeyboardButton urgentSectBtn = new InlineKeyboardButton();
        urgentSectBtn.setText("🚨 Shoshilinch bo'lim");
        urgentSectBtn.setCallbackData(CB_SECTION_URG + appId);
        rows.add(List.of(normalSectBtn, urgentSectBtn));

        // 5. Suxbat + Yopish
        InlineKeyboardButton chatBtn = new InlineKeyboardButton();
        chatBtn.setText("Suxbat boshlash"); chatBtn.setCallbackData(CB_START_CHAT + appId);
        InlineKeyboardButton closeBtn = new InlineKeyboardButton();
        closeBtn.setText("Yopish"); closeBtn.setCallbackData(CB_CLOSE_APP + appId);
        rows.add(List.of(chatBtn, closeBtn));

        // 6. Word barchasi
        InlineKeyboardButton exportAllBtn = new InlineKeyboardButton();
        exportAllBtn.setText("Word (barchasi)"); exportAllBtn.setCallbackData(CB_EXPORT_ALL);
        rows.add(List.of(exportAllBtn));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    // ─── Word export ──────────────────────────────────────────────────────
    private void handleWordExportAll(String chatId) {
        try {
            byte[] data = exportService.exportToWord();
            SendDocument send = new SendDocument();
            send.setChatId(chatId);
            send.setDocument(new InputFile(new ByteArrayInputStream(data), "murojaatlar.docx"));
            send.setCaption("Barcha murojaatlar. Jami: " + repository.count() + " ta");
            execute(send);
        } catch (Exception e) {
            log.error("Word export xatolik: {}", e.getMessage());
            sendMessage(chatId, "Word export xatolik: " + e.getMessage());
        }
    }

    private void handleWordExportOne(String chatId, Long appId) {
        try {
            byte[] data = exportService.exportSingleToWord(appId);
            SendDocument send = new SendDocument();
            send.setChatId(chatId);
            send.setDocument(new InputFile(new ByteArrayInputStream(data), "murojaat_" + appId + ".docx"));
            send.setCaption("Murojaat #" + appId);
            execute(send);
        } catch (Exception e) {
            log.error("Word export xatolik [appId={}]: {}", appId, e.getMessage());
            sendMessage(chatId, "Xatolik: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  YORDAMCHI METODLAR
    // ═══════════════════════════════════════════════════════════════════════
    private void sendMessageWithEndChatButton(String chatId, String text, String lang, Long appId) {
        try {
            SendMessage msg = new SendMessage(chatId, text);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            InlineKeyboardButton endBtn = new InlineKeyboardButton();
            endBtn.setText(switch (lang) {
                case "en" -> "End chat";
                case "ru" -> "Завершить чат";
                default   -> "Suxbatni yakunlash";
            });
            endBtn.setCallbackData(CB_END_CHAT + appId);
            markup.setKeyboard(List.of(List.of(endBtn)));
            msg.setReplyMarkup(markup);
            execute(msg);
        } catch (Exception e) {
            log.error("sendMessageWithEndChatButton xato: {}", e.getMessage());
        }
    }

    private void sendAdminEndChatButton(String adminId, Long appId) {
        try {
            SendMessage msg = new SendMessage(adminId, "Suxbatni yakunlash uchun:");
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            InlineKeyboardButton endBtn = new InlineKeyboardButton();
            endBtn.setText("Suxbatni yakunlash (#" + appId + ")");
            endBtn.setCallbackData(CB_END_CHAT + appId);
            markup.setKeyboard(List.of(List.of(endBtn)));
            msg.setReplyMarkup(markup);
            execute(msg);
        } catch (Exception e) {
            log.error("sendAdminEndChatButton xato: {}", e.getMessage());
        }
    }

    private void notifyOtherAdmins(String excludeAdminId, String text) {
        for (String adminId : adminChatIds) {
            if (!adminId.equals(excludeAdminId)) sendMessage(adminId, text);
        }
    }

    private void saveChatMessage(Long appId, String senderType, String senderId, String text) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setAppId(appId);
            msg.setSenderType(senderType);
            msg.setSenderId(senderId);
            msg.setMessage(text);
            msg.setSentAt(LocalDateTime.now());
            chatMessageRepository.save(msg);
        } catch (Exception e) {
            log.error("saveChatMessage xato: {}", e.getMessage());
        }
    }

    /**
     * [YANGI] saveApplication — applicantPhone ham saqlanadi.
     * BotStateService.getUserPhone(chatId) orqali olinadi.
     */
    private Application saveApplication(String chatId, String lang, String text,
                                        Long parentId, String fileId, String fileType) {
        Application app = new Application();
        app.setApplicantName(state.getUserName(chatId));
        app.setPhoneNumber(state.getPhoneNumber(chatId));  // [YANGI]
        app.setDescription(text);
        app.setLang(lang);
        app.setChatId(chatId);
        app.setParentId(parentId);
        app.setFileId(fileId);
        app.setFileType(fileType);
        app.setPriority(Priority.NORMAL);
        return repository.save(app);
    }

    private String buildConfirmText(String lang, Long id, String timeStr, Priority priority) {
        return i18n.get(lang, "id").replace("{id}", id.toString()) + "\n"
                + i18n.get(lang, "time").replace("{time}", timeStr) + "\n"
                + i18n.get(lang, "status")
                + (Priority.URGENT == priority ? "\n" + i18n.get(lang, "priority_urgent") : "");
    }

    private void sendWithMainButtons(String chatId, String text, String lang, Long appId)
            throws TelegramApiException {
        SendMessage msg = new SendMessage(chatId, text);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        InlineKeyboardButton addBtn = new InlineKeyboardButton();
        addBtn.setText(i18n.get(lang, "btn_add_more")); addBtn.setCallbackData(CB_ADD_MORE);
        rows.add(List.of(addBtn));

        InlineKeyboardButton checkBtn = new InlineKeyboardButton();
        checkBtn.setText(i18n.get(lang, "btn_check_status")); checkBtn.setCallbackData(CB_CHECK_STATUS + appId);
        InlineKeyboardButton myAppsBtn = new InlineKeyboardButton();
        myAppsBtn.setText(i18n.get(lang, "btn_my_apps")); myAppsBtn.setCallbackData(CB_MY_APPS);
        rows.add(List.of(checkBtn, myAppsBtn));

        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    private Optional<Long> parseId(String data, String prefix) {
        try { return Optional.of(Long.parseLong(data.substring(prefix.length()))); }
        catch (Exception e) { return Optional.empty(); }
    }

    private void sendMessage(String chatId, String text) {
        try { execute(new SendMessage(chatId, text)); }
        catch (TelegramApiException e) {
            log.error("sendMessage xato [chatId={}]: {}", chatId, e.getMessage());
        }
    }

    private String getPriorityTag(Priority priority) {
        return priority == Priority.URGENT ? "[SHOSHILINCH] " : "";
    }

    private String getStatusText(String lang, ApplicationStatus status) {
        if (status == null) return "—";
        return switch (status) {
            case PENDING   -> switch (lang) { case "en" -> "Under review"; case "ru" -> "На рассмотрении"; default -> "Ko'rib chiqilmoqda"; };
            case IN_REVIEW -> switch (lang) { case "en" -> "In progress";  case "ru" -> "В процессе";      default -> "Jarayonda"; };
            case REPLIED   -> switch (lang) { case "en" -> "Replied";       case "ru" -> "Отвечено";         default -> "Javob berildi"; };
            case CLOSED    -> switch (lang) { case "en" -> "Closed";        case "ru" -> "Закрыто";          default -> "Yopildi"; };
        };
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatDuration(int seconds) {
        if (seconds < 60) return seconds + " sek";
        int min = seconds / 60, sec = seconds % 60;
        if (min < 60) return min + ":" + String.format("%02d", sec);
        int h = min / 60, m = min % 60;
        return h + ":" + String.format("%02d", m) + ":" + String.format("%02d", sec);
    }
}