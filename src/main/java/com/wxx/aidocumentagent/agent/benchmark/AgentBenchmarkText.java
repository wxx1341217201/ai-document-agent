package com.wxx.aidocumentagent.agent.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AgentBenchmarkText {
    private static final Pattern THINK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private AgentBenchmarkText() { }
    static String visible(String raw) {
        if (raw == null) return null;
        String text = THINK.matcher(raw).replaceAll("").strip();
        if (text.toLowerCase(Locale.ROOT).contains("<think") || text.toLowerCase(Locale.ROOT).contains("</think")) {
            throw new IllegalArgumentException("HIDDEN_REASONING_REJECTED");
        }
        return text;
    }
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static String canonicalHash(ObjectMapper mapper, Object value) {
        return sha256(mapper.writeValueAsString(sorted(mapper.readTree(mapper.writeValueAsString(value)))));
    }
    private static Object sorted(JsonNode node) {
        if (node.isObject()) {
            var entries = new TreeMap<String, Object>();
            node.properties().forEach(e -> entries.put(e.getKey(), sorted(e.getValue())));
            return entries;
        }
        if (node.isArray()) {
            var values = new ArrayList<>();
            node.forEach(v -> values.add(sorted(v)));
            return values;
        }
        return node;
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
