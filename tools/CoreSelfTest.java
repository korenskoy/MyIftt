package dev.myiftt.forwarder;

import java.util.List;

public final class CoreSelfTest {
    public static void main(String[] args) {
        assert ForwardRules.matchesSms("NSKBL", "Приход 100 RUR", "", "nskbl");
        assert ForwardRules.matchesSms("BANK", "nskbl: приход", "bank", "NSKBL");
        assert !ForwardRules.matchesSms("OTHER", "nskbl: приход", "bank", "nskbl");
        assert !ForwardRules.matchesSms("BANK", "приход", "", "");
        assert ForwardRules.matchesSms("BANK", "приход", "BANK", "");
        assert ForwardRules.matchesBank("bank.pkg", "bank.pkg", "Баланс", "100 rur", "RUR");
        assert !ForwardRules.matchesBank("other.pkg", "bank.pkg", "Баланс", "100 RUR", "RUR");
        assert !ForwardRules.matchesBank("bank.pkg", "bank.pkg", "", "", "");
        assert ForwardRules.matchesAny("", "Перевод получен", "покупка\nперевод");
        assert ForwardRules.matchesAny("ПОКУПКА", "", " покупка \r\n перевод ");
        assert !ForwardRules.matchesAny("", "Баланс", "покупка\n\nперевод");
        assert ForwardRules.matchesAny(null, null, " \n\n ");
        assert ForwardRules.matchesBank("bank.pkg", "bank.pkg", "Баланс", "Перевод", "RUR\nперевод");
        assert ForwardRules.matchesSms("BANK", "Перевод", "bank", "покупка\nперевод");
        assert !ForwardRules.matchesSms("OTHER", "Перевод", "bank", "покупка\nперевод");
        assert !ForwardRules.matchesSms("BANK", "Перевод", "", " \n \n");
        assert !ForwardRules.matchesSms("PRIVATE", "Личное сообщение", " \t ", " \n \n");
        assert ForwardRules.message("Title", "Body", "Bank", 0).startsWith("Title\nBody\n\nBank\n");
        String text = "a".repeat(4095) + "😀" + "b".repeat(4100);
        List<String> chunks = ForwardRules.split(text);
        assert String.join("", chunks).equals(text);
        for (String chunk : chunks) {
            assert chunk.length() <= 4096;
            assert !Character.isHighSurrogate(chunk.charAt(chunk.length() - 1));
            assert !Character.isLowSurrogate(chunk.charAt(0));
        }
        assert ForwardRules.split("").isEmpty();
        assert ForwardRules.hash("event1").equals(ForwardRules.hash("event1"));
        assert !ForwardRules.hash("event1").equals(ForwardRules.hash("event2"));
        assert ForwardRules.retryable(429) && ForwardRules.retryable(503) && ForwardRules.retryable(408);
        assert !ForwardRules.retryable(400) && !ForwardRules.retryable(401) && !ForwardRules.retryable(403);
        assert ForwardRules.retryDelay(0, 120) == 120_000;
        assert ForwardRules.retryDelay(0, 90_000) == 90_000_000L;
        assert ForwardRules.retryDelay(30, 0) == 3_600_000;
        assert ForwardRules.retryDelay(0, 0) == 30_000;
        System.out.println("Core checks passed: filters, message format, Unicode splitting, event hashes, retry policy.");
    }
}
