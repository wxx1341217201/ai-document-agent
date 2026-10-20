package com.wxx.aidocumentagent.agent;

import java.util.regex.Pattern;

/** 只识别答案明确声明的证据缺口；不将这一启发式当作语义正确性判定。 */
public final class EvidenceAnswerSupport {
    public static final String NO_EVIDENCE = "现有资料不足以回答这个问题，请补充相关资料。";
    private static final Pattern GAP = Pattern.compile(
            "(?is).*(资料不足|证据不足|无法(?:从[^。\\n]{0,80})?回答|无法确定|未提供[^。\\n]{0,50}证据|"
                    + "\\b(cannot answer|can't answer|unable to answer|insufficient evidence|"
                    + "not enough (information|evidence)|cannot be determined)\\b).*"
    );
    private EvidenceAnswerSupport() { }
    public static boolean declaresGap(String answer) { return answer != null && GAP.matcher(answer).matches(); }

    /** Query-language hint only; no aliases, answers or dataset labels are introduced. */
    public static String retrievalLanguageHint(java.util.List<String> passages) {
        String text = String.join("\n", passages);
        long han = text.codePoints().filter(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN).count();
        long latin = text.codePoints().filter(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN).count();
        long englishMarkers = Pattern.compile("(?i)\\b(the|and|of|to|was|were|she|he|his|her|said|with|that|this|for|from|which|would)\\b")
                .matcher(text).results().count();
        if (latin > Math.max(200, han * 4) && englishMarkers >= 20) return """
                RETRIEVAL LANGUAGE: The actual passages are predominantly English.
                Every missing retrieval target MUST be a concise English search query, retaining the source's English subject names
                and the requested event/predicate. Do not put Chinese explanations or parenthetical analysis in missing targets.
                Translate the query only; do not invent facts. The final claim text may use the user's language.
                """;
        return "";
    }
}
