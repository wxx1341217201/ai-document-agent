package com.wxx.aidocumentagent.rag.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class BenchmarkText {

    private static final Pattern THINK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private static final Pattern REMAINING_THINK = Pattern.compile("(?is)</?think\\b");

    private BenchmarkText() { }

    static String visibleAnswer(String answer) {
        if (answer == null) {
            return null;
        }
        String visible = THINK.matcher(answer).replaceAll("").strip();
        if (REMAINING_THINK.matcher(visible).find()) {
            throw new IllegalArgumentException("UNSAFE_MODEL_OUTPUT");
        }
        return visible;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", " ").strip();
    }

    static boolean containsPhrase(String text, String phrase) {
        String normalized = normalize(text);
        String target = normalize(phrase);
        // 单字中文不强制词界；英文必须完整词，避免 cat 命中 Catherine。
        String boundary = target.matches(".*[a-z0-9].*") ? "(?<![a-z0-9])%s(?![a-z0-9])" : "%s";
        return Pattern.compile(boundary.formatted(Pattern.quote(target))).matcher(normalized).find();
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
