package dev.myiftt.forwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()) || !Settings.isEnabled(context)) return;
        String senderFilter = Settings.get(context, "sms_sender").trim();
        String phrases = Settings.get(context, "sms_filter");
        if (senderFilter.isEmpty() && phrases.trim().isEmpty()) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            String preview = "";
            try {
                SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (parts == null || parts.length == 0) return;
                String sender = parts[0].getDisplayOriginatingAddress();
                if (sender == null) sender = "";
                if (!senderFilter.isEmpty() && !sender.equalsIgnoreCase(senderFilter)) {
                    EventLog.record(context, "SMS", "Пропущено", "Отправитель не совпал с указанным в правиле.", "");
                    return;
                }
                StringBuilder body = new StringBuilder();
                for (SmsMessage part : parts) {
                    String text = part.getMessageBody();
                    if (text != null) body.append(text);
                }
                preview = "От: " + sender + "\n" + body;
                if (!ForwardRules.matchesSms(sender, body.toString(), senderFilter, phrases)) {
                    EventLog.record(context, "SMS", "Пропущено", "Ни одна из фраз не найдена в отправителе или тексте.", preview);
                    return;
                }
                long timestamp = parts[0].getTimestampMillis();
                String event = ForwardRules.hash("sms:" + sender + ":" + timestamp + ":" + body);
                if (Outbox.get(context).enqueue(event, event,
                        ForwardRules.message("SMS от " + sender, body.toString(), "SMS", timestamp),
                        Settings.get(context, "chat_id"), false)) {
                    EventLog.record(context, "SMS", "В очереди", "Условия выполнены. Ожидает отправки в Telegram.", preview);
                    ForwardJob.schedule(context);
                } else EventLog.record(context, "SMS", "Дубликат", "Это SMS уже было принято.", preview);
            } catch (Exception e) {
                EventLog.record(context, "SMS", "Ошибка", "Не удалось сохранить SMS в очередь.", preview);
                Settings.status(context, "Не удалось сохранить SMS. Проверьте свободное место и настройки.");
            } finally {
                pending.finish();
            }
        }, "sms-forward").start();
    }
}
