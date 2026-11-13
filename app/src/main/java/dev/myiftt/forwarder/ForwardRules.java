package dev.myiftt.forwarder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ForwardRules {
    private ForwardRules() { }

    public static boolean contains(String text, String filter) {
        return safe(text).toLowerCase(Locale.ROOT).contains(safe(filter).trim().toLowerCase(Locale.ROOT));
    }

    public static boolean matchesAny(String first, String second, String newlinePhrases) {
        boolean hasPhrase = false;
        for (String phrase : safe(newlinePhrases).split("\\r?\\n")) {
            if (phrase.trim().isEmpty()) continue;
            hasPhrase = true;
            if (contains(first, phrase) || contains(second, phrase)) return true;
        }
        return !hasPhrase;
    }

    public static boolean matchesSms(String sender, String body, String senderFilter, String keyword) {
        if (safe(senderFilter).trim().isEmpty() && safe(keyword).trim().isEmpty()) return false;
        return (safe(senderFilter).trim().isEmpty() || safe(sender).equalsIgnoreCase(senderFilter.trim()))
                && matchesAny(sender, body, keyword);
    }

    public static boolean matchesBank(String packageName, String selectedPackage, String title, String body, String keyword) {
        return !safe(selectedPackage).isEmpty() && selectedPackage.equals(packageName)
                && matchesAny(title, body, keyword)
                && !(safe(title).isEmpty() && safe(body).isEmpty());
    }

    public static String message(String title, String body, String source, long time) {
        return (safe(title).isEmpty() ? "" : title + "\n") + safe(body)
                + "\n\n" + source + "\n" + DateFormat.getDateTimeInstance().format(new Date(time));
    }

    public static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 4096, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static boolean retryable(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    public static long retryDelay(int attempts, long retryAfterSeconds) {
        long backoff = Math.min(3_600_000L, 30_000L << Math.min(attempts, 7));
        return Math.max(backoff, Math.max(0, Math.min(retryAfterSeconds, Integer.MAX_VALUE)) * 1000L);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
