package com.wxx.aidocumentagent.rag.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Generic lexical tie-breaker and document intent, using only the request and candidate text. */
public final class EvidenceRelevance {
    private static final Set<String> STOP = Set.of("a", "an", "and", "are", "as", "at", "be", "been", "by", "can", "did", "do", "does",
            "for", "from", "had", "has", "have", "he", "her", "him", "his", "how", "i", "in", "is", "it", "its", "of", "on", "or",
            "she", "that", "the", "their", "them", "there", "these", "they", "this", "to", "was", "were", "what", "when", "where",
            "which", "who", "why", "with", "would", "you", "your", "then", "answer", "book", "document", "documents", "compare",
            "contrast", "context", "adjacent", "metadata", "read", "fetch", "search", "quote", "cite", "source", "sources", "using",
            "explain", "describe", "please", "first", "same", "scene", "both", "long", "txt", "pdf", "md", "author", "title");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private EvidenceRelevance() { }

    public static List<String> terms(String text) {
        return WORD.matcher(normalize(text)).results().map(java.util.regex.MatchResult::group)
                .filter(term -> term.length() > 1 && !STOP.contains(term)).distinct().limit(80).toList();
    }
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'');
    }
    public static double score(String question, String content) {
        String normalized = " " + normalize(content).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        var terms = terms(question);
        double score = 0;
        for (String term : terms) {
            if (normalized.contains(" " + term + " ")) score += 1 + Math.min(term.length(), 12) / 12.0;
            else if (term.length() > 4 && normalized.contains(term.substring(0, term.length() - (term.endsWith("ing") ? 3 : 1)))) score += .3;
        }
        for (int i = 0; i + 1 < terms.size(); i++) if (normalized.contains(terms.get(i) + " " + terms.get(i + 1))) score += 1.5;
        return score;
    }
    public static boolean metadata(String question) {
        return Pattern.compile("(?is).*(\\b(title|author|authorship|written by|wrote the|name of the book)\\b|书名|作者|著者).*")
                .matcher(question == null ? "" : question).matches();
    }
    public static boolean comparison(String question) {
        return Pattern.compile("(?is).*(\\b(compare|contrast|across|versus|both)\\b|比较|对比|跨文档|跨两|两本|两份).*")
                .matcher(question == null ? "" : question).matches();
    }
    public static int documentAffinity(String question, String name) {
        var query = terms(question);
        return (int) terms(name).stream().filter(query::contains).count();
    }
}
