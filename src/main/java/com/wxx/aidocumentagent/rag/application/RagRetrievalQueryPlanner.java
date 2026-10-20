package com.wxx.aidocumentagent.rag.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** 有界、确定性的检索扩展；只使用用户问题中的原文，不生成答案或参考事实。 */
public final class RagRetrievalQueryPlanner {

    public static final String VERSION = "bounded-query-expansion-v3-candidate-pool";
    private static final int MAX_QUERY_CHARACTERS = 2_000;
    private static final Pattern REFERENCE = Pattern.compile(
            "(?is).*(它|他|她|其|该|上述|这个|那个|这些|那些|\\b(it|its|he|she|him|her|his|they|them|their|this|that|these|those)\\b).*"
    );
    private static final Pattern COMPOUND = Pattern.compile(
            "(?is).*(\\b(compare|contrast|across|versus|both|explain|describe|identify|list)\\b|比较|对比|分别|跨|两份|两本|以及|说明|解释|列出).*"
    );
    private static final Pattern QUESTION_WORD = Pattern.compile(
            "(?i)\\b(who|what|which|where|when|how|why)\\b|什么|哪里|哪个|多少|谁|如何|为何"
    );
    private static final Pattern CLAUSE = Pattern.compile(
            "(?i)\\s+(?:and|with|versus|vs\\.?)\\s+|[，,；;]|(?:以及|并且|与|和)"
    );
    private static final Pattern ENTITY = Pattern.compile("\\b[A-Z][a-z][\\p{L}\\p{M}-]*\\b|《[^》]{1,80}》");
    private static final java.util.Set<String> INSTRUCTIONS = java.util.Set.of(
            "What", "Which", "Who", "Where", "When", "How", "Why", "Compare", "Contrast", "Across", "Explain",
            "Describe", "Identify", "List", "Read", "Fetch", "Check", "Please", "Then", "Both", "The", "In");
    private static final Pattern GENERIC_FOLLOW_UP = Pattern.compile(
            "(?is).*(同一场景|同一个场景|刚才讨论|上述内容|刚才提到|same (scene|subject|topic)|previous (scene|subject|topic)).*");

    public List<String> plan(String question, List<RagConversationTurn> history, boolean enabled, int maxQueries) {
        if (question == null || question.isBlank() || question.length() > MAX_QUERY_CHARACTERS
                || maxQueries < 1 || maxQueries > 3) {
            throw new IllegalArgumentException("Invalid retrieval query plan");
        }
        String topic = "";
        if (enabled && history != null && (question.length() <= 24 || REFERENCE.matcher(question).matches())) {
            // 助手的历史回答不能将未经验证的事实引入检索问题。
            for (int i = history.size() - 1; i >= 0; i--) {
                var turn = history.get(i);
                if (turn != null && turn.role() == RagConversationTurn.Role.USER && !turn.content().equals(question)) {
                    topic = truncate(turn.content().strip(), Math.max(0, Math.min(256, MAX_QUERY_CHARACTERS - question.length() - 1)));
                    if (!GENERIC_FOLLOW_UP.matcher(topic).matches()) break;
                    // “继续同一场景”没有具体主题；再向前找到用户原始主题，不能借用助手回答。
                    for (int earlier = i - 1; earlier >= 0; earlier--) {
                        var source = history.get(earlier);
                        if (source != null && source.role() == RagConversationTurn.Role.USER
                                && !GENERIC_FOLLOW_UP.matcher(source.content()).matches()) {
                            topic = truncate(source.content().strip() + "；" + topic,
                                    Math.max(0, Math.min(256, MAX_QUERY_CHARACTERS - question.length() - 1)));
                            break;
                        }
                    }
                    break;
                }
            }
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(withTopic(topic, question));
        if (enabled && maxQueries > 1 && (COMPOUND.matcher(question).matches()
                || QUESTION_WORD.matcher(question).results().count() >= 2)) {
            // 多个问句优先于“在两个系统中”这样的引导语，避免引导语占用有限检索名额。
            List<String> clauses = java.util.Arrays.stream(CLAUSE.split(question)).map(String::strip)
                    .sorted(java.util.Comparator.comparing((String text) -> QUESTION_WORD.matcher(text).find()).reversed())
                    .toList();
            for (String clause : clauses) {
                String text = clause.strip();
                if (text.length() >= 4 && !text.equals(question)) {
                    String scope = topic;
                    if (scope.isBlank()) {
                        List<String> entities = ENTITY.matcher(question).results().map(java.util.regex.MatchResult::group)
                                .filter(entity -> !INSTRUCTIONS.contains(entity)).distinct().toList();
                        if (entities.stream().noneMatch(text::contains)) scope = String.join(" ", entities);
                    }
                    queries.add(withTopic(truncate(scope, Math.max(0, MAX_QUERY_CHARACTERS - text.length() - 1)), text));
                }
                if (queries.size() == maxQueries) {
                    break;
                }
            }
        }
        return List.copyOf(queries);
    }

    private String withTopic(String topic, String question) {
        return topic.isBlank() ? question : topic + "；" + question;
    }

    private String truncate(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }
}
