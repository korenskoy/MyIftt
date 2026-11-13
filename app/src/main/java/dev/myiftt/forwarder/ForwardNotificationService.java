package dev.myiftt.forwarder;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ForwardNotificationService extends NotificationListenerService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onNotificationPosted(StatusBarNotification item) {
        if (item == null || (item.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        worker.execute(() -> {
            String app = item.getPackageName();
            String preview = "";
            try {
                if (!Settings.isEnabled(this) || !item.getPackageName().equals(Settings.get(this, "bank_package"))) return;
                try {
                    app = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(item.getPackageName(), 0)).toString();
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                Notification notification = item.getNotification();
                String title = notification.extras == null ? "" : value(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
                CharSequence big = notification.extras == null ? null : notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                String body = notification.extras == null ? "" : value(big == null ? notification.extras.getCharSequence(Notification.EXTRA_TEXT) : big);
                preview = title + (title.isEmpty() ? "" : "\n") + body;
                if (!ForwardRules.matchesBank(item.getPackageName(), Settings.get(this, "bank_package"),
                        title, body, Settings.get(this, "bank_filter"))) {
                    EventLog.record(this, app, "Пропущено", title.isEmpty() && body.isEmpty()
                            ? "Android не передал текст уведомления." : "Ни одна из фраз не найдена в заголовке или тексте.", preview);
                    return;
                }
                String text = ForwardRules.message(title, body, app, item.getPostTime());
                if (Outbox.get(this).enqueue(event(item),
                        ForwardRules.hash(title + "\n" + body), text, Settings.get(this, "chat_id"), false)) {
                    EventLog.record(this, app, "В очереди", "Условия выполнены. Ожидает отправки в Telegram.", preview);
                    ForwardJob.schedule(this);
                } else EventLog.record(this, app, "Дубликат", "Это уведомление уже было принято.", preview);
            } catch (Exception e) {
                EventLog.record(this, app, "Ошибка", "Не удалось сохранить уведомление в очередь.", preview);
                Settings.status(this, "Не удалось сохранить уведомление. Проверьте свободное место и настройки.");
            }
        });
    }

    @Override public void onNotificationRemoved(StatusBarNotification item) {
        if (item != null) worker.execute(() -> {
            try { Outbox.get(this).forget(event(item)); }
            catch (Exception e) { Settings.status(this, "Не удалось обновить защиту от повторов."); }
        });
    }

    @Override public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private static String value(CharSequence text) { return text == null ? "" : text.toString(); }

    private static String event(StatusBarNotification item) {
        // shortcut: identity uses the app's event timestamp; use bank-specific IDs if it reuses timestamps.
        long time = item.getNotification().when > 0 ? item.getNotification().when : item.getPostTime();
        return ForwardRules.hash("notification:" + item.getKey() + ":" + time);
    }
}
