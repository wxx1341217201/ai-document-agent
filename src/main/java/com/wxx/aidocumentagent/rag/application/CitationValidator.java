package com.wxx.aidocumentagent.rag.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型只可选择程序提供的 C#，不能把模型文本当作引用元数据来源。
 * 一旦出现非法、缺失或没有引用的答案，整段模型文本都会被拒绝，避免部分清洗后仍保留不可验证结论。
 */
public final class CitationValidator {

    private static final Pattern HIDDEN_REASONING = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private static final Pattern CITATION = Pattern.compile("\\[C([1-9]\\d*)]");
    private static final Pattern CITATION_SHAPED = Pattern.compile("\\[C[^]]*]");

    public CitationValidation validate(String rawAnswer, List<RagCitation> availableCitations) {
        String answer = sanitize(rawAnswer);
        if (answer.isBlank()) {
            return new CitationValidation(false, "", List.of());
        }

        Map<String, RagCitation> available = new LinkedHashMap<>();
        for (RagCitation citation : availableCitations) {
            available.put(citation.citationId(), citation);
        }
        Map<String, RagCitation> used = new LinkedHashMap<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            String citationId = "C" + matcher.group(1);
            RagCitation citation = available.get(citationId);
            if (citation == null) {
                return new CitationValidation(false, "", List.of());
            }
            used.putIfAbsent(citationId, citation);
        }
        // [Cfoo]、[C0] 等看似引用但不符合稳定 ID 格式时同样拒绝，不能静默漏检。
        Matcher shapedMatcher = CITATION_SHAPED.matcher(answer);
        while (shapedMatcher.find()) {
            if (!CITATION.matcher(shapedMatcher.group()).matches()) {
                return new CitationValidation(false, "", List.of());
            }
        }
        if (used.isEmpty()) {
            return new CitationValidation(false, "", List.of());
        }
        return new CitationValidation(true, answer, List.copyOf(used.values()));
    }

    private String sanitize(String rawAnswer) {
        if (rawAnswer == null) {
            return "";
        }
        // 标准 Spring AI 文本响应不含隐藏推理；此处作为供应商异常输出的防线，且原文不会被存储。
        return HIDDEN_REASONING.matcher(rawAnswer).replaceAll("").strip();
    }
}
