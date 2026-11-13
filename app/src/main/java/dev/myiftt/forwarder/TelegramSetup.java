package dev.myiftt.forwarder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds chats only when the user presses the setup button. */
public final class TelegramSetup {
    private TelegramSetup() {}

    public record Chat(String id, String title) {}

    public static List<Chat> findChats(String token) throws IOException {
        token = token.trim();
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]{20,}")) {
            throw new IOException("Проверь токен бота.");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0")
                    .openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code == 409) {
                throw new IOException("Бот уже подключён к другому сервису. Введи ID чата вручную.");
            }
            if (code == 401 || code == 404) {
                throw new IOException("Telegram не принял токен бота.");
            }
            if (code != 200) {
                throw new IOException("Telegram недоступен (" + code + "). Попробуй позже.");
            }
            JSONObject response;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 1_048_576) {
                        throw new IOException("Слишком большой ответ Telegram. Введи ID чата вручную.");
                    }
                    bytes.write(buffer, 0, count);
                }
                response = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
            if (!response.optBoolean("ok")) {
                throw new IOException("Не удалось получить чаты Telegram.");
            }
            return chatsFromUpdates(response.getJSONArray("result"));
        } catch (JSONException e) {
            throw new IOException("Не удалось прочитать ответ Telegram.");
        } catch (IOException e) {
            // Network exception messages can contain the URL, including the bot token.
            if (e.getMessage() != null && e.getMessage().contains(token)) {
                throw new IOException("Не удалось связаться с Telegram. Проверь интернет.");
            }
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static List<Chat> chatsFromUpdates(JSONArray updates) {
        Map<String, Chat> chats = new LinkedHashMap<>();
        for (int i = 0; i < updates.length(); i++) {
            JSONObject update = updates.optJSONObject(i);
            if (update == null) continue;
            for (String type : new String[]{"message", "channel_post", "my_chat_member"}) {
                JSONObject event = update.optJSONObject(type);
                JSONObject chat = event == null ? null : event.optJSONObject("chat");
                if (chat == null || !chat.has("id")) continue;
                String id = chat.optString("id", "");
                if (id.isEmpty()) continue;
                String title = chat.optString("title", "");
                if (title.isEmpty()) {
                    title = (chat.optString("first_name", "") + " "
                            + chat.optString("last_name", "")).trim();
                }
                if (title.isEmpty()) title = chat.optString("username", id);
                chats.put(id, new Chat(id, title));
            }
        }
        return new ArrayList<>(chats.values());
    }
}
