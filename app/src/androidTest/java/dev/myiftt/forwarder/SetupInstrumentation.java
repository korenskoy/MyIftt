package dev.myiftt.forwarder;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import org.json.JSONArray;

import java.io.IOException;
import java.util.List;

/** Runs setup checks on Android without credentials, network calls or SMS access. */
public final class SetupInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            List<TelegramSetup.Chat> chats = TelegramSetup.chatsFromUpdates(new JSONArray("["
                    + "{\"message\":{\"chat\":{\"id\":-1001234567890,\"title\":\"Группа\"}}},"
                    + "{\"channel_post\":{\"chat\":{\"id\":-1001234567890,\"title\":\"Группа\"}}},"
                    + "{\"my_chat_member\":{\"chat\":{\"id\":1234567890123,\"first_name\":\"Антон\"}}},"
                    + "{\"message\":{}},null,{}]"));
            require(chats.size() == 2, "Chat IDs must be deduplicated");
            require(chats.get(0).id().equals("-1001234567890"), "Group ID precision");
            require(chats.get(1).title().equals("Антон"), "Private chat label");
            require(TelegramSetup.chatsFromUpdates(new JSONArray()).isEmpty(), "Empty updates");
            try {
                TelegramSetup.findChats("invalid token");
                throw new AssertionError("Invalid token accepted");
            } catch (IOException expected) {
                require(!expected.getMessage().contains("invalid token"), "Token in error");
            }
            OutboxChecks.run(getTargetContext());
            EventLogChecks.run(getTargetContext());
            Throwable[] uiFailure = new Throwable[1];
            runOnMainSync(() -> {
                try { ConditionEditorChecks.run(getTargetContext()); }
                catch (Throwable error) { uiFailure[0] = error; }
            });
            if (uiFailure[0] != null) throw new AssertionError("Condition editor check failed", uiFailure[0]);
            result.putString("stream", "PASS: chat discovery, encrypted outbox, FIFO retries, pause, encrypted event log, last-100 limit, persistence, clear, condition editor\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
