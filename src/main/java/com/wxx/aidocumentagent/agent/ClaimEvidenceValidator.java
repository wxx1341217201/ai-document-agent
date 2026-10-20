package com.wxx.aidocumentagent.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared, bounded answer protocol. Provenance is deterministic; semantic support needs a separate check. */
public final class ClaimEvidenceValidator {
    public static final String INSTRUCTION = """
            When giving a final answer, return ONLY this JSON object (no markdown or reasoning):
            {"status":"ANSWERED","claims":[{"text":"one concise requested fact, without citation markers",
              "supports":[{"citationId":"C1","quote":"an exact contiguous quote copied from that passage"}]}],"missing":[]}
            Use status PARTIAL when any requested fact is missing; list at most four specific missing retrieval targets.
            Write missing retrieval targets in the source language when it is visible; retain the subject and exact scene.
            Use status INSUFFICIENT with claims=[] and missing=["the requested missing information"] if nothing is supported.
            Each claim needs 1-3 direct source quotes. Preserve the source spelling, punctuation and line breaks in quotes.
            Quotes must include the subject, event and requested quantity/time/unit, not merely a matching name.
            Prefer ONE most direct passage per fact. Do not cite nearby background or a different scene.
            Answer comparisons as separate grounded inputs and a grounded comparison. Include all requested details.
            Do not add optional background. Do not invent source IDs, offsets, metadata or facts from history.
            Include only facts needed by the current question; source ownership, background plot and extra details are not needed unless asked.
            Stay within the output budget: normally 1-3 short atomic claims, about 120 characters each,
            with one concise quote of about 240 characters per fact. Use a second quote only for necessary complementary support.
            Avoid repeating the same fact in a separate comparison paragraph. Prefer complete JSON under 1000 tokens.
            Maximum 12 claims, 1500 characters per claim, 1600 per quote. No other keys.
            """;
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)\\d+(?:[.,]\\d+)?(?!\\d)");

    public enum State { ANSWERED, PARTIAL, INSUFFICIENT }
    public enum Error { INVALID_SCHEMA, INVALID_JSON, INVALID_STATUS, INVALID_CLAIM, INVALID_SUPPORT,
        UNKNOWN_CITATION, SCOPE_MISMATCH, QUOTE_NOT_VISIBLE, NUMERIC_MISMATCH }

    public record Visible(AgentCitation citation, String text, String sourceVersionSha256) {
        public Visible(AgentCitation citation, String text) { this(citation, text, null); }
    }
    public record Result(State state, String answer, List<AgentCitation> citations,
                         List<ClaimEvidenceBinding> bindings, List<String> missing, Error error) {
        public boolean valid() { return error == null; }
        public boolean complete() { return valid() && state != State.PARTIAL; }
    }

    public Result validate(String raw, long knowledgeBaseId, List<Visible> visible) {
        try {
            if (raw == null || raw.length() > 40_000 || raw.toLowerCase().contains("<think")
                    || raw.toLowerCase().contains("</think")) return invalid(Error.INVALID_SCHEMA);
            raw = raw.strip();
            if (raw.startsWith("```json") && raw.endsWith("```")) raw = raw.substring(7, raw.length() - 3).strip();
            JsonNode root = MAPPER.readTree(raw);
            if (!keys(root, Set.of("status", "claims", "missing")) || !root.path("status").isTextual()
                    || !root.path("claims").isArray() || root.path("claims").size() > 12
                    || !root.path("missing").isArray() || root.path("missing").size() > 4) return invalid(Error.INVALID_SCHEMA);
            State state;
            try { state = State.valueOf(root.get("status").asText()); }
            catch (IllegalArgumentException invalidState) { return invalid(Error.INVALID_STATUS); }
            List<String> missing = new ArrayList<>();
            for (JsonNode item : root.get("missing")) {
                if (!safeText(item, 400)) return invalid(Error.INVALID_SCHEMA);
                missing.add(item.asText());
            }
            if ((state == State.ANSWERED) != missing.isEmpty()
                    || (state == State.INSUFFICIENT) != root.get("claims").isEmpty()) return invalid(Error.INVALID_SCHEMA);
            Map<String, Visible> byId = new LinkedHashMap<>();
            for (Visible item : visible) {
                if (item.citation().knowledgeBaseId() != knowledgeBaseId) return invalid(Error.SCOPE_MISMATCH);
                if (item.sourceVersionSha256() != null && !item.sourceVersionSha256().matches("[a-f0-9]{64}")) return invalid(Error.INVALID_SCHEMA);
                if (byId.putIfAbsent(item.citation().citationId(), item) != null) return invalid(Error.INVALID_SCHEMA);
            }
            List<ClaimEvidenceBinding> bindings = new ArrayList<>();
            Map<String, AgentCitation> used = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>();
            for (JsonNode claim : root.get("claims")) {
                if (!keys(claim, Set.of("text", "supports")) || !safeClaimText(claim.path("text"))
                        || !claim.path("supports").isArray() || claim.path("supports").isEmpty()
                        || claim.path("supports").size() > 3) return invalid(Error.INVALID_CLAIM);
                List<ClaimEvidenceBinding.SourceSpan> spans = new ArrayList<>();
                Map<String, AgentCitation> local = new LinkedHashMap<>();
                StringBuilder quotes = new StringBuilder();
                for (JsonNode support : claim.get("supports")) {
                    if (!keys(support, Set.of("citationId", "quote")) || !support.path("citationId").isTextual()
                            || !safeText(support.path("quote"), 1_600)) return invalid(Error.INVALID_SUPPORT);
                    Visible source = byId.get(support.get("citationId").asText());
                    if (source == null) return invalid(Error.UNKNOWN_CITATION);
                    String quote = support.get("quote").asText();
                    int[] span = locate(source.text(), quote);
                    if (span == null) return invalid(Error.QUOTE_NOT_VISIBLE);
                    int start = span[0], end = span[1];
                    quote = source.text().substring(start, end);
                    spans.add(new ClaimEvidenceBinding.SourceSpan(source.citation(), source.sourceVersionSha256(), sha256(source.text()),
                            start, end, quote));
                    local.putIfAbsent(source.citation().citationId(), source.citation());
                    used.putIfAbsent(source.citation().citationId(), source.citation());
                    quotes.append(quote).append('\n');
                }
                String claimText = claim.get("text").asText();
                var inline = Pattern.compile("\\[C([1-9]\\d*)]").matcher(claimText);
                while (inline.find()) if (!local.containsKey("C" + inline.group(1))) return invalid(Error.UNKNOWN_CITATION);
                String plainText = Pattern.compile("\\[C([1-9]\\d*)]").matcher(claimText).replaceAll("").strip();
                if (plainText.isBlank() || plainText.contains("[C")) return invalid(Error.INVALID_CLAIM);
                // Explicit digits are checked conservatively. Word numbers, units and relations are checked semantically.
                var numbers = NUMBER.matcher(plainText).results().map(java.util.regex.MatchResult::group).toList();
                if (NUMBER.matcher(quotes).find() && numbers.stream().anyMatch(number -> !NUMBER.matcher(quotes).results()
                        .anyMatch(match -> match.group().replace(",", "").equals(number.replace(",", ""))))) return invalid(Error.NUMERIC_MISMATCH);
                bindings.add(new ClaimEvidenceBinding(plainText, spans));
                lines.add(plainText + " " + local.keySet().stream()
                        .map(id -> "[" + id + "]").collect(java.util.stream.Collectors.joining()));
            }
            String answer = state == State.INSUFFICIENT ? EvidenceAnswerSupport.NO_EVIDENCE : String.join("\n", lines);
            if (state == State.PARTIAL) answer += "\n资料不足，尚未解决：" + String.join("；", missing);
            return new Result(state, answer, List.copyOf(used.values()), List.copyOf(bindings), List.copyOf(missing), null);
        }
        catch (tools.jackson.core.JacksonException invalid) { return invalid(Error.INVALID_JSON); }
        catch (RuntimeException invalid) { return invalid(Error.INVALID_SCHEMA); }
    }

    private boolean safeClaimText(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 1_500
                && !Pattern.compile("(?is)<\\/?think|BEGIN_UNTRUSTED|END_UNTRUSTED").matcher(value.asText()).find();
    }

    private boolean safeText(JsonNode value, int maximum) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= maximum
                && !Pattern.compile("(?is)\\[C|<\\/?think|BEGIN_UNTRUSTED|END_UNTRUSTED").matcher(value.asText()).find();
    }
    private int[] locate(String text, String quote) {
        if (text == null) return null;
        int direct = text.indexOf(quote);
        if (direct >= 0) return new int[]{direct, direct + quote.length()};
        // Models may flatten printed line wraps. Locate the real contiguous span and keep its actual whitespace.
        String expression = java.util.Arrays.stream(quote.strip().split("\\s+"))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        var match = Pattern.compile(expression).matcher(text);
        return match.find() ? new int[]{match.start(), match.end()} : null;
    }
    private static boolean keys(JsonNode node, Set<String> keys) {
        return node != null && node.isObject() && node.size() == keys.size()
                && node.properties().stream().allMatch(entry -> keys.contains(entry.getKey()));
    }
    private static Result invalid(Error error) { return new Result(null, null, List.of(), List.of(), List.of(), error); }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
