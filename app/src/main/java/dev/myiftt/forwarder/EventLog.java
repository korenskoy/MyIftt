package dev.myiftt.forwarder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class EventLog extends SQLiteOpenHelper {
    private static EventLog instance;

    private static synchronized EventLog get(Context context) {
        if (instance == null) instance = new EventLog(context.getApplicationContext(), "events.db");
        return instance;
    }

    EventLog(Context context, String databaseName) { super(context, databaseName, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, payload TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported event log version");
    }

    public static void record(Context context, String source, String outcome, String reason, String preview) {
        try {
            get(context).append(source, outcome, reason, preview);
        } catch (Exception ignored) {
            // Diagnostics must not prevent a notification from reaching the durable outbox.
        }
    }

    public static List<Entry> recent(Context context) throws Exception { return get(context).entries(); }

    public static void clear(Context context) { get(context).erase(); }

    synchronized void append(String source, String outcome, String reason, String preview) throws Exception {
        JSONObject payload = new JSONObject().put("source", safe(source)).put("outcome", safe(outcome))
                .put("reason", safe(reason)).put("preview", preview(preview));
        ContentValues values = new ContentValues();
        values.put("at", System.currentTimeMillis());
        values.put("payload", Secrets.encrypt(payload.toString()));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertOrThrow("events", null, values);
            db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 100)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized List<Entry> entries() throws Exception {
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("events", new String[]{"at", "payload"},
                null, null, null, null, "id DESC", "100")) {
            while (cursor.moveToNext()) {
                JSONObject payload = new JSONObject(Secrets.decrypt(cursor.getString(1)));
                result.add(new Entry(cursor.getLong(0), payload.getString("source"), payload.getString("outcome"),
                        payload.getString("reason"), payload.getString("preview")));
            }
        }
        return result;
    }

    synchronized void erase() { getWritableDatabase().delete("events", null, null); }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String preview(String value) {
        value = safe(value);
        if (value.length() <= 500) return value;
        int end = 499;
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end) + "…";
    }

    public static final class Entry {
        public final long time;
        public final String source;
        public final String outcome;
        public final String reason;
        public final String preview;

        Entry(long time, String source, String outcome, String reason, String preview) {
            this.time = time;
            this.source = source;
            this.outcome = outcome;
            this.reason = reason;
            this.preview = preview;
        }
    }
}
