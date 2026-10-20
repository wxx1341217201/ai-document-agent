package com.wxx.aidocumentagent.rag.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只删掉另一已选证据中完整保留的同文档重复尾部；正文仍是精确原文前缀。 */
public final class EvidenceOverlap {
    private EvidenceOverlap() { }
    public record Part(long chunkId, long documentId, String text, int minimumPrefix) { }

    public static Map<Long, String> prefixes(List<Part> parts) {
        Map<Long, String> result = new LinkedHashMap<>();
        parts.forEach(part -> result.put(part.chunkId(), part.text()));
        for (Part earlier : parts) {
            int minimum = Math.max(500, earlier.minimumPrefix());
            for (Part later : parts) {
                if (earlier.chunkId() == later.chunkId() || earlier.documentId() != later.documentId()
                        || earlier.text().equals(later.text())) continue;
                int maximum = Math.min(1_500, Math.min(later.text().length(), earlier.text().length() - minimum));
                for (int overlap = maximum; overlap >= 160; overlap--) {
                    int cut = earlier.text().length() - overlap;
                    if (Character.isHighSurrogate(earlier.text().charAt(cut - 1))) continue;
                    if (earlier.text().regionMatches(cut, later.text(), 0, overlap)) {
                        if (cut < result.get(earlier.chunkId()).length()) result.put(earlier.chunkId(), earlier.text().substring(0, cut));
                        break;
                    }
                }
            }
        }
        // 链式重叠也必须有一个最终保留的片段完整包含被删尾部，否则撤销该次截断。
        for (Part part : parts) {
            String visible = result.get(part.chunkId());
            if (visible.length() == part.text().length()) continue;
            String removed = part.text().substring(visible.length());
            boolean preserved = parts.stream().anyMatch(other -> other.chunkId() != part.chunkId()
                    && other.documentId() == part.documentId() && result.get(other.chunkId()).contains(removed));
            if (!preserved) result.put(part.chunkId(), part.text());
        }
        return Map.copyOf(result);
    }
}
