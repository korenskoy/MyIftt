package dev.myiftt.forwarder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;

final class Outbox extends SQLiteOpenHelper {
    private static Outbox instance;

    static synchronized Outbox get(Context context) {
        if (instance == null) instance = new Outbox(context.getApplicationContext());
        return instance;
    }

    private Outbox(Context context) { this(context, "outbox.db"); }

    Outbox(Context context, String databaseName) { super(context, databaseName, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, "
                + "is_test INTEGER NOT NULL, due INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE seen (event TEXT PRIMARY KEY, digest TEXT NOT NULL, at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported outbox version");
    }

    synchronized boolean enqueue(String event, String contentDigest, String text, String chat, boolean test) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            db.delete("seen", "at < ?", new String[]{Long.toString(now - 86_400_000L)});
            if (event != null) {
                try (Cursor cursor = db.query("seen", new String[]{"digest"}, "event=?", new String[]{event}, null, null, null)) {
                    if (cursor.moveToFirst() && contentDigest.equals(cursor.getString(0))) {
                        db.setTransactionSuccessful();
                        return false;
                    }
                }
            }
            for (String part : ForwardRules.split(text)) {
                JSONObject payload = new JSONObject().put("chat_id", chat).put("text", part);
                ContentValues row = new ContentValues();
                row.put("payload", Secrets.encrypt(payload.toString()));
                row.put("is_test", test ? 1 : 0);
                db.insertOrThrow("messages", null, row);
            }
            if (event != null) {
                ContentValues seen = new ContentValues();
                seen.put("event", event);
                seen.put("digest", contentDigest);
                seen.put("at", now);
                db.insertWithOnConflict("seen", null, seen, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    synchronized void forget(String event) {
        getWritableDatabase().delete("seen", "event=?", new String[]{event});
    }

    synchronized Message next(boolean enabled) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "payload", "attempts", "due"},
                "failed=0" + (enabled ? "" : " AND is_test=1"), null, null, null, "id", "1")) {
            if (!cursor.moveToFirst()) return null;
            return new Message(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3));
        }
    }

    synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    synchronized void sent(long id) { getWritableDatabase().delete("messages", "id=?", new String[]{Long.toString(id)}); }

    synchronized void clear() { getWritableDatabase().delete("messages", null, null); }

    synchronized void retry(Message message, long delay, boolean permanent) {
        ContentValues values = new ContentValues();
        values.put("attempts", message.attempts + 1);
        values.put("due", System.currentTimeMillis() + delay);
        values.put("failed", permanent ? 1 : 0);
        getWritableDatabase().update("messages", values, "id=?", new String[]{Long.toString(message.id)});
    }

    synchronized void retryFailed() {
        getWritableDatabase().execSQL("UPDATE messages SET failed=0, due=0, attempts=0 WHERE failed=1");
    }

    static final class Message {
        final long id;
        final String payload;
        final int attempts;
        final long due;
        Message(long id, String payload, int attempts, long due) {
            this.id = id; this.payload = payload; this.attempts = attempts; this.due = due;
        }
        JSONObject decrypt() throws Exception { return new JSONObject(Secrets.decrypt(payload)); }
    }
}
