package dev.myiftt.forwarder;

import android.content.Context;
import android.content.SharedPreferences;

public final class Settings {
    private Settings() { }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static String get(Context context, String key) {
        String fallback = key.equals("bank_filter") ? "RUR" : key.equals("sms_filter") ? "nskbl" : "";
        String value = prefs(context).getString(key, fallback);
        if (!key.equals("bot_token") || value.isEmpty()) return value;
        try {
            return Secrets.decrypt(value);
        } catch (Exception e) {
            status(context, "Не удалось прочитать токен. Введите его заново.");
            return "";
        }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    static void validateCredentials(String token, String chatId) {
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]{20,}"))
            throw new IllegalArgumentException("Введите токен бота из BotFather.");
        if (!chatId.matches("-?[0-9]+|@[A-Za-z][A-Za-z0-9_]{4,}"))
            throw new IllegalArgumentException("Введите числовой ID чата или @имя канала.");
    }

    public static synchronized void save(Context context, boolean enabled, String token, String chatId,
            String bankPackage, String bankFilter, String smsSender, String smsFilter) throws Exception {
        token = token.trim();
        chatId = chatId.trim();
        if (enabled) {
            validateCredentials(token, chatId);
            if (bankPackage.isEmpty() && smsSender.trim().isEmpty() && smsFilter.trim().isEmpty())
                throw new IllegalArgumentException("Выберите приложение банка или задайте фильтр SMS.");
        }
        String encryptedToken = token.isEmpty() ? "" : Secrets.encrypt(token);
        if (!prefs(context).edit().putBoolean("enabled", enabled).putString("bot_token", encryptedToken)
                .putString("chat_id", chatId).putString("bank_package", bankPackage)
                .putString("bank_filter", bankFilter.trim()).putString("sms_sender", smsSender.trim())
                .putString("sms_filter", smsFilter.trim()).commit())
            throw new java.io.IOException("Не удалось сохранить настройки.");
        Outbox.get(context).retryFailed();
        ForwardJob.schedule(context);
    }

    static void status(Context context, String text) {
        prefs(context).edit().putString("status", text).apply();
    }
}
