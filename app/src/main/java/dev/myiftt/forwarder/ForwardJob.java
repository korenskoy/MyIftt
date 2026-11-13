package dev.myiftt.forwarder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ForwardJob extends JobService {
    private static final int JOB_ID = 1;
    private static final Object LOCK = new Object();
    private static Run running;
    private Run current;

    public static void enqueueTest(Context context) throws Exception {
        Settings.validateCredentials(Settings.get(context, "bot_token"), Settings.get(context, "chat_id"));
        Outbox.get(context).enqueue(null, null, "MyIFTT: проверка отправки. Связь с Telegram работает.",
                Settings.get(context, "chat_id"), true);
        EventLog.record(context, "Telegram", "В очереди", "Тестовая отправка по нажатию кнопки.", "");
        schedule(context);
    }

    public static int pendingCount(Context context) { return Outbox.get(context).count(); }

    public static void clearQueue(Context context) {
        synchronized (LOCK) {
            Run run = running;
            if (run != null) stop(run);
            context.getSystemService(JobScheduler.class).cancel(JOB_ID);
            Outbox.get(context).clear();
            if (run != null) run.rescheduleAfterStop = true;
            Settings.status(context, "Очередь удалена. Уже принятые Telegram сообщения удалить нельзя.");
            EventLog.record(context, "Telegram", "Очередь удалена",
                    "Пользователь удалил ожидающие сообщения. Уже принятый запрос может завершиться.", "");
        }
    }

    public static void schedule(Context context) {
        synchronized (LOCK) {
            if (running != null) return;
            try {
                Outbox.Message next = Outbox.get(context).next(Settings.isEnabled(context));
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (next == null) {
                    scheduler.cancel(JOB_ID);
                    return;
                }
                JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ForwardJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setMinimumLatency(Math.max(0, Math.max(next.due,
                                Settings.prefs(context).getLong("telegram_not_before", 0)) - System.currentTimeMillis()))
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
                if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE)
                    Settings.status(context, "Android не запланировал отправку. Откройте приложение ещё раз.");
            } catch (Exception e) {
                Settings.status(context, "Не удалось открыть очередь отправки.");
            }
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        Run run = new Run();
        current = run;
        synchronized (LOCK) { running = run; }
        run.worker = new Thread(() -> {
            try {
                drain(run);
            } catch (Exception e) {
                synchronized (LOCK) {
                    if (!run.stopped) {
                        Settings.status(this, "Ошибка очереди. Откройте приложение и проверьте настройки.");
                        EventLog.record(this, "Telegram", "Ошибка", "Не удалось обработать очередь отправки.", "");
                    }
                }
            } finally {
                synchronized (LOCK) {
                    if (running == run) running = null;
                    if (!run.stopped) {
                        jobFinished(params, false);
                        schedule(this);
                    } else if (run.rescheduleAfterStop) schedule(this);
                }
            }
        }, "telegram-forward");
        run.worker.start();
        return true;
    }

    private void drain(Run run) throws Exception {
        Outbox box = Outbox.get(this);
        for (int count = 0; count < 50 && !run.stopped; count++) {
            Outbox.Message message = box.next(Settings.isEnabled(this));
            if (message == null || Math.max(message.due,
                    Settings.prefs(this).getLong("telegram_not_before", 0)) > System.currentTimeMillis()) return;
            String preview = "";
            try {
                JSONObject body = message.decrypt();
                preview = body.optString("text", "");
                String token = Settings.get(this, "bot_token");
                Settings.validateCredentials(token, body.getString("chat_id"));
                Result result = send(token, body, run);
                synchronized (LOCK) {
                    if (run.stopped) return;
                    if (result.ok) {
                        box.sent(message.id);
                        Settings.status(this, "Отправлено в Telegram: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
                        EventLog.record(this, "Telegram", "Доставлено", "Telegram подтвердил отправку.", preview);
                    } else {
                        boolean retry = ForwardRules.retryable(result.code);
                        long delay = ForwardRules.retryDelay(message.attempts, result.retryAfter);
                        if (result.code == 429) Settings.prefs(this).edit().putLong("telegram_not_before",
                                System.currentTimeMillis() + delay).apply();
                        box.retry(message, delay, !retry);
                        Settings.status(this, retry ? "Telegram временно недоступен. Повторим отправку автоматически."
                                : "Telegram отклонил отправку (" + result.code + "). Исправьте настройки и сохраните их для повтора.");
                        EventLog.record(this, "Telegram", retry ? "Повтор" : "Ошибка",
                                "Код Telegram: " + result.code + ". " + (retry
                                        ? "Повтор через " + delay / 1000 + " с."
                                        : "Исправьте настройки и сохраните их для повтора."), preview);
                        if (retry) return;
                    }
                }
            } catch (java.io.IOException e) {
                synchronized (LOCK) {
                    if (run.stopped) return;
                    long delay = ForwardRules.retryDelay(message.attempts, 0);
                    box.retry(message, delay, false);
                    Settings.status(this, "Нет связи с Telegram. Сообщение сохранено для повторной отправки.");
                    EventLog.record(this, "Telegram", "Повтор",
                            "Нет связи с Telegram. Повтор через " + delay / 1000 + " с.", preview);
                }
                return;
            } catch (Exception e) {
                synchronized (LOCK) {
                    if (run.stopped) return;
                    box.retry(message, 0, true);
                    Settings.status(this, "Не удалось прочитать сообщение или токен. Проверьте настройки и сохраните их для повтора.");
                    EventLog.record(this, "Telegram", "Ошибка",
                            "Не удалось прочитать сообщение или проверить настройки. Сохраните настройки для повтора.", preview);
                }
            }
        }
    }

    private Result send(String token, JSONObject body, Run run) throws Exception {
        HttpURLConnection active = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
        run.connection = active;
        try {
            if (run.stopped) throw new java.io.InterruptedIOException();
            active.setRequestMethod("POST");
            active.setConnectTimeout(15_000);
            active.setReadTimeout(20_000);
            active.setInstanceFollowRedirects(false);
            active.setDoOutput(true);
            active.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            body.put("link_preview_options", new JSONObject().put("is_disabled", true));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            active.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = active.getOutputStream()) { output.write(bytes); }
            int code = active.getResponseCode();
            InputStream response = code >= 400 ? active.getErrorStream() : active.getInputStream();
            JSONObject result = new JSONObject();
            if (response != null) {
                try (InputStream input = response) {
                    java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int size;
                    while (data.size() < 65_536 && (size = input.read(buffer, 0,
                            Math.min(buffer.length, 65_536 - data.size()))) != -1) data.write(buffer, 0, size);
                    try { result = new JSONObject(data.toString(StandardCharsets.UTF_8.name())); }
                    catch (org.json.JSONException ignored) { }
                }
            }
            boolean ok = code >= 200 && code < 300 && result.optBoolean("ok", false);
            int error = result.optInt("error_code", code);
            if (!ok && error < 400) error = 503;
            JSONObject parameters = result.optJSONObject("parameters");
            return new Result(ok, error, parameters == null ? 0 : parameters.optLong("retry_after", 0));
        } finally {
            active.disconnect();
            run.connection = null;
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        Run run = current;
        if (run == null) return true;
        stop(run);
        return true;
    }

    private static void stop(Run run) {
        run.stopped = true;
        HttpURLConnection active = run.connection;
        if (active != null) active.disconnect();
        if (run.worker != null) run.worker.interrupt();
    }

    private static final class Run {
        volatile boolean stopped;
        boolean rescheduleAfterStop;
        volatile HttpURLConnection connection;
        Thread worker;
    }

    private static final class Result {
        final boolean ok;
        final int code;
        final long retryAfter;
        Result(boolean ok, int code, long retryAfter) { this.ok = ok; this.code = code; this.retryAfter = retryAfter; }
    }
}
