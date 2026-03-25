package com.example.demo.bot;

import org.springframework.stereotype.Component;

/**
 * I18nService — Ko'p tilli matnlar (uz, ru, en).
 *
 * O'zgarishlar (v8):
 *   - ask_fullname  — ism-familya so'rash
 *   - ask_phone     — telefon raqami so'rash
 *   - contact_saved — kontakt saqlandi
 *   - deadline_info — deadline ma'lumoti
 *   - deadline_overdue — deadline o'tib ketdi (foydalanuvchiga)
 */
@Component
public class I18nService {

    public String get(String lang, String key) {
        return switch (lang) {
            case "en" -> en(key);
            case "ru" -> ru(key);
            default   -> uz(key);
        };
    }

    // ─── O'zbek ──────────────────────────────────────────────────────────
    private String uz(String key) {
        return switch (key) {
            case "welcome"           -> "Assalomu alaykum! Tilni tanlang:\nЗдравствуйте! Выберите язык:\nHello! Select a language:";
            case "ask_fullname"      -> "👤 Ism va familyangizni kiriting (masalan: Aliyev Akbar):";
            case "ask_phone"         -> "📱 Telefon raqamingizni kiriting yoki pastdagi tugmani bosing:";
            case "contact_saved"     -> "✅ Ma'lumotlaringiz saqlandi!";
            case "ask_desc"          -> "📝 Murojaatingizni yuboring. Rasm, fayl, audio yoki video ham yuborishingiz mumkin. 😊";
            case "ask_additional"    -> "📎 Qo'shimcha ma'lumot yuboring (matn, rasm, fayl, audio, video):";
            case "thanks"            -> "✅ Murojaatingiz qabul qilindi.\nMutaxassislarimiz tez orada bog'lanadi.";
            case "additional_saved"  -> "➕ Qo'shimcha ma'lumot qo'shildi.";
            case "id"                -> "🔖 Murojaat raqami: #{id}";
            case "time"              -> "🕐 Yuborilgan vaqt: {time}";
            case "deadline_info"     -> "📅 Bajarish muddati: {deadline}";
            case "status"            -> "📌 Holat: ⏳ Ko'rib chiqilmoqda";
            case "btn_add_more"      -> "➕ Qo'shimcha yuborish";
            case "btn_check_status"  -> "📊 Holatni tekshirish";
            case "btn_my_apps"       -> "📋 Mening murojaatlarim";
            case "no_apps"           -> "📭 Sizda hozircha murojaat yo'q.";
            case "admin_reply"       -> "Admin javobi";
            case "no_reply"          -> "Hali javob yo'q. Mutaxassislar ko'rib chiqmoqda.";
            case "status_label"      -> "Holat";
            case "priority_urgent"   -> "Shoshilinch murojaat";
            case "send_file_hint"    -> "📝 Iltimos, /start bilan boshlang.";
            case "restart"           -> "Iltimos, /start buyrug'i bilan qayta boshlang.";
            case "unsupported_media" -> "❓ Bu media turi qo'llab-quvvatlanmaydi. Matn, rasm, fayl, audio yoki video yuboring.";
            case "cancelled"         -> "❌ Murojaat bekor qilindi. Qayta boshlash uchun /start bosing.";
            case "nothing_to_cancel" -> "ℹ️ Bekor qilish uchun faol jarayon yo'q.";
            case "help"              -> """
                    📋 Mavjud buyruqlar:
                    /start  — Yangi murojaat yuborish
                    /myapps — Mening murojaatlarim
                    /status — Oxirgi murojaatim holati
                    /cancel — Jarayonni bekor qilish
                    /help   — Yordam""";
            case "status_not_found"  -> "❌ Sizda hali murojaat yo'q. /start bilan boshlang.";
            case "flood_warning"     -> "⏳ Juda tez xabar yuboryapsiz. Biroz kuting.";
            case "error_user"        -> "⚠️ Xatolik yuz berdi. Qayta urinib ko'ring yoki /start bosing.";
            case "chat_timeout"      -> "⏱ Suxbat faolsizlik sababli yakunlandi (30 daqiqa).";
            case "media_album_note"  -> "📷 Album qabul qilindi. Bitta murojaat sifatida saqlanadi.";
            case "deadline_overdue"  -> "⚠️ #{id} raqamli murojaatingiz bajarish muddati ({deadline}) o'tib ketdi. Tez orada javob beriladi.";
            case "invalid_phone" -> "📱 Noto'g'ri raqam. Iltimos, to'g'ri telefon raqam kiriting:";

            default -> "Xatolik yuz berdi. Qayta urinib ko'ring.";
        };
    }

    // ─── Rus ─────────────────────────────────────────────────────────────
    private String ru(String key) {
        return switch (key) {
            case "welcome"           -> "Assalomu alaykum! Tilni tanlang:\nЗдравствуйте! Выберите язык:\nHello! Select a language:";
            case "ask_fullname"      -> "👤 Введите ваше имя и фамилию (например: Иванов Иван):";
            case "ask_phone"         -> "📱 Введите номер телефона или нажмите кнопку ниже:";
            case "contact_saved"     -> "✅ Ваши данные сохранены!";
            case "ask_desc"          -> "📝 Опишите ваше обращение. Можно отправить фото, файл, аудио или видео. 😊";
            case "ask_additional"    -> "📎 Отправьте дополнительную информацию (текст, фото, файл, аудио, видео):";
            case "thanks"            -> "✅ Ваше обращение принято.\nНаши специалисты свяжутся с вами.";
            case "additional_saved"  -> "➕ Дополнительная информация добавлена.";
            case "id"                -> "🔖 Номер обращения: #{id}";
            case "time"              -> "🕐 Время подачи: {time}";
            case "deadline_info"     -> "📅 Срок исполнения: {deadline}";
            case "status"            -> "📌 Статус: ⏳ На рассмотрении";
            case "btn_add_more"      -> "➕ Добавить информацию";
            case "btn_check_status"  -> "📊 Проверить статус";
            case "btn_my_apps"       -> "📋 Мои обращения";
            case "no_apps"           -> "📭 У вас ещё нет обращений.";
            case "admin_reply"       -> "Ответ администратора";
            case "no_reply"          -> "Ответа пока нет. Специалисты рассматривают.";
            case "status_label"      -> "Статус";
            case "priority_urgent"   -> "Срочное обращение";
            case "send_file_hint"    -> "📝 Сначала начните с /start.";
            case "restart"           -> "Пожалуйста, начните заново с /start.";
            case "unsupported_media" -> "❓ Этот тип медиа не поддерживается. Отправьте текст, фото, файл, аудио или видео.";
            case "cancelled"         -> "❌ Обращение отменено. Начните заново с /start.";
            case "nothing_to_cancel" -> "ℹ️ Нет активного процесса для отмены.";
            case "help"              -> """
                    📋 Доступные команды:
                    /start  — Отправить новое обращение
                    /myapps — Мои обращения
                    /status — Статус последнего обращения
                    /cancel — Отменить текущий процесс
                    /help   — Помощь""";
            case "status_not_found"  -> "❌ У вас нет обращений. Начните с /start.";
            case "flood_warning"     -> "⏳ Вы отправляете сообщения слишком быстро. Подождите.";
            case "error_user"        -> "⚠️ Произошла ошибка. Попробуйте снова или нажмите /start.";
            case "chat_timeout"      -> "⏱ Чат завершён из-за неактивности (30 минут).";
            case "media_album_note"  -> "📷 Альбом получен. Будет сохранён как одно обращение.";
            case "deadline_overdue"  -> "⚠️ Срок исполнения обращения №{id} ({deadline}) истёк. Скоро ответим.";
            case "invalid_phone" -> "📱 Неверный номер. Введите корректный номер телефона:";
            default -> "Произошла ошибка. Попробуйте снова.";
        };
    }

    // ─── English ─────────────────────────────────────────────────────────
    private String en(String key) {
        return switch (key) {
            case "welcome"           -> "Assalomu alaykum! Tilni tanlang:\nЗдравствуйте! Выберите язык:\nHello! Select a language:";
            case "ask_fullname"      -> "👤 Please enter your full name (e.g. John Smith):";
            case "ask_phone"         -> "📱 Please enter your phone number or press the button below:";
            case "contact_saved"     -> "✅ Your information has been saved!";
            case "ask_desc"          -> "📝 Please describe your request. You can also send photos, files, audio, or video. 😊";
            case "ask_additional"    -> "📎 Send additional information (text, photo, file, audio, video):";
            case "thanks"            -> "✅ Your request has been received.\nOur specialists will contact you soon.";
            case "additional_saved"  -> "➕ Additional information has been added.";
            case "id"                -> "🔖 Request ID: #{id}";
            case "time"              -> "🕐 Submitted: {time}";
            case "deadline_info"     -> "📅 Deadline: {deadline}";
            case "status"            -> "📌 Status: ⏳ Under review";
            case "btn_add_more"      -> "➕ Add more info";
            case "btn_check_status"  -> "📊 Check status";
            case "btn_my_apps"       -> "📋 My requests";
            case "no_apps"           -> "📭 You have no requests yet.";
            case "admin_reply"       -> "Admin reply";
            case "no_reply"          -> "No reply yet. Our specialists are reviewing it.";
            case "status_label"      -> "Status";
            case "priority_urgent"   -> "Urgent request";
            case "send_file_hint"    -> "📝 Please start with /start first.";
            case "restart"           -> "Please restart with /start.";
            case "unsupported_media" -> "❓ This media type is not supported. Please try text, photo, file, audio or video.";
            case "cancelled"         -> "❌ Request cancelled. Use /start to begin again.";
            case "nothing_to_cancel" -> "ℹ️ There is no active process to cancel.";
            case "help"              -> """
                    📋 Available commands:
                    /start  — Submit a new request
                    /myapps — My requests
                    /status — Last request status
                    /cancel — Cancel current process
                    /help   — Help""";
            case "status_not_found"  -> "❌ You have no requests yet. Use /start to begin.";
            case "flood_warning"     -> "⏳ You are sending messages too fast. Please wait.";
            case "error_user"        -> "⚠️ An error occurred. Please try again or press /start.";
            case "chat_timeout"      -> "⏱ Chat session ended due to inactivity (30 minutes).";
            case "media_album_note"  -> "📷 Album received. It will be saved as a single request.";
            case "deadline_overdue"  -> "⚠️ The deadline ({deadline}) for request #{id} has passed. We'll respond soon.";
            case "invalid_phone" -> "📱 Invalid number. Please enter a correct phone number:";
            default -> "An error occurred. Please try again.";
        };
    }
}