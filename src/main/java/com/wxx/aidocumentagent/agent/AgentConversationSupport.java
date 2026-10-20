package com.wxx.aidocumentagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.wxx.aidocumentagent.rag.application.RagConversationTurn;

/** 有界话题上下文；历史用于指代消解，旧引用不能成为本轮证据。 */
public final class AgentConversationSupport {
    private static final Pattern OLD_CITATION = Pattern.compile("\\[C[^]]*]");
    private static final Pattern THINK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private AgentConversationSupport() { }

    public static List<RagConversationTurn> history(List<AgentMemoryMessage> messages) {
        List<RagConversationTurn> newest = new ArrayList<>();
        int remaining = 4_000;
        for (int i = messages.size() - 1; i >= 0 && newest.size() < 10 && remaining > 0; i--) {
            var message = messages.get(i);
            String text = OLD_CITATION.matcher(THINK.matcher(message.content()).replaceAll(""))
                    .replaceAll("").strip();
            text = prefix(text, Math.min(1_000, remaining));
            if (text.isBlank()) continue;
            newest.add(new RagConversationTurn(message.role() == AgentMemoryMessage.Role.USER
                    ? RagConversationTurn.Role.USER : RagConversationTurn.Role.ASSISTANT, text));
            remaining -= text.length();
        }
        return List.copyOf(newest.reversed());
    }

    public static String prompt(List<RagConversationTurn> history) {
        StringBuilder result = new StringBuilder("Conversation context for reference resolution only (NOT evidence):\n");
        for (var turn : history) result.append(turn.role()).append(": ").append(turn.content()).append('\n');
        return result.toString();
    }

    public static String prefix(String text, int maxCharacters) {
        if (text.length() <= maxCharacters) return text;
        int end = maxCharacters;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
