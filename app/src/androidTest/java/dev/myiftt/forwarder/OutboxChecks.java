package dev.myiftt.forwarder;

import android.content.Context;
import android.database.Cursor;

final class OutboxChecks {
    private OutboxChecks() { }

    static void run(Context context) throws Exception {
        String databaseName = "qa-outbox-" + System.nanoTime() + ".db";
        Outbox box = new Outbox(context, databaseName);
        String text = "OUTBOX_PRIVATE_TEXT_123456789";
        String chat = "-1009876543210";
        try {
            require(box.enqueue("event-one", "content-one", text, chat, false), "First event rejected");
            Outbox.Message first = box.next(true);
            require(first != null, "Queued event missing");
            long firstId = first.id;
            require(first.decrypt().getString("text").equals(text), "Payload decryption failed");
            require(first.decrypt().getString("chat_id").equals(chat), "Destination snapshot lost");
            try (Cursor cursor = box.getReadableDatabase().rawQuery("SELECT payload FROM messages", null)) {
                require(cursor.moveToFirst(), "Encrypted row missing");
                String stored = cursor.getString(0);
                require(!stored.contains(text) && !stored.contains(chat), "Plaintext in outbox");
            }
            require(!box.enqueue("event-one", "content-one", text, chat, false), "Duplicate event accepted");
            require(box.enqueue("event-one", "content-two", "Changed notification", chat, false),
                    "Changed notification rejected");
            require(box.count() == 2, "Deduplication count");

            box.close();
            box = new Outbox(context, databaseName);
            require(box.count() == 2, "Queue lost after reopen");
            first = box.next(true);
            require(first != null && first.id == firstId, "Queue order lost after reopen");
            require(first.decrypt().getString("chat_id").equals(chat), "Snapshot lost after reopen");

            box.retry(first, 60_000, false);
            Outbox.Message delayedHead = box.next(true);
            require(delayedHead != null && delayedHead.id == firstId, "Later chunk overtook failed head");
            require(delayedHead.due > System.currentTimeMillis(), "Retry delay not persisted");
            require(box.next(false) == null, "Paused queue exposed normal message");
            require(box.enqueue(null, null, "User-requested test", chat, true), "Test enqueue failed");
            Outbox.Message test = box.next(false);
            require(test != null && test.decrypt().getString("text").equals("User-requested test"),
                    "Test unavailable while forwarding paused");
            require(box.next(true).id == firstId, "Test changed FIFO order");

            box.clear();
            require(box.count() == 0 && box.next(true) == null && box.next(false) == null,
                    "Clear left queued messages");
            require(!box.enqueue("event-one", "content-two", "Changed notification", chat, false),
                    "Clear removed deduplication history");

            require(box.enqueue("event-three", "content-three", "Recovery", chat, false), "Recovery enqueue");
            Outbox.Message failed = box.next(true);
            box.retry(failed, 0, true);
            require(box.count() == 1 && box.next(true) == null, "Permanent failure was lost or retried");
            box.retryFailed();
            Outbox.Message recovered = box.next(true);
            require(recovered != null && recovered.id == failed.id && recovered.due == 0 && recovered.attempts == 0,
                    "Failed row did not recover");
            box.clear();
            require(box.count() == 0, "Final test cleanup failed");
        } finally {
            box.close();
            require(context.deleteDatabase(databaseName), "Temporary test database cleanup failed");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
