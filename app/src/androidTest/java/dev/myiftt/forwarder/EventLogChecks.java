package dev.myiftt.forwarder;

import android.content.Context;
import android.database.Cursor;

import java.util.List;

final class EventLogChecks {
    private EventLogChecks() { }

    static void run(Context context) throws Exception {
        String databaseName = "qa-events-" + System.nanoTime() + ".db";
        EventLog log = new EventLog(context, databaseName);
        String secret = "EVENT_LOG_PRIVATE_987654321";
        try {
            for (int i = 0; i < 105; i++) log.append("SMS", "Пропущено", "Reason " + i, secret + i);
            List<EventLog.Entry> entries = log.entries();
            require(entries.size() == 100, "Journal cap must be 100");
            require(entries.get(0).preview.equals(secret + "104"), "Newest event must appear first");
            require(entries.get(99).preview.equals(secret + "5"), "Oldest journal events not trimmed");
            require(entries.get(0).time > 0 && entries.get(0).source.equals("SMS"), "Journal fields lost");
            try (Cursor cursor = log.getReadableDatabase().rawQuery("SELECT payload FROM events LIMIT 1", null)) {
                require(cursor.moveToFirst(), "Encrypted journal row missing");
                String payload = cursor.getString(0);
                require(!payload.contains(secret) && !payload.contains("Reason"), "Journal contains plaintext");
            }
            log.close();
            log = new EventLog(context, databaseName);
            require(log.entries().size() == 100, "Journal lost after reopening");
            StringBuilder longPreview = new StringBuilder();
            for (int i = 0; i < 498; i++) longPreview.append('a');
            log.append("Bank", "В очереди", "Unicode", longPreview + "😀tail");
            String preview = log.entries().get(0).preview;
            require(preview.length() <= 500 && preview.endsWith("…"), "Journal preview not bounded");
            require(!Character.isHighSurrogate(preview.charAt(preview.length() - 2)), "Journal split emoji");
            log.erase();
            require(log.entries().isEmpty(), "Journal clear failed");
        } finally {
            log.close();
            require(context.deleteDatabase(databaseName), "Temporary journal cleanup failed");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
