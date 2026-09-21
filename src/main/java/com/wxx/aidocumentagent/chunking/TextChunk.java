package com.wxx.aidocumentagent.chunking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 尚未绑定数据库主键的文本块。{@link #contentHash()} 始终由内容计算，保证重复切分可复现。
 */
public record TextChunk(
        int chunkIndex,
        String content,
        int tokenCount,
        Integer pageFrom,
        Integer pageTo,
        String sectionTitle,
        Map<String, String> metadata) {

    public TextChunk {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex不能小于0");
        }
        content = Objects.requireNonNull(content, "content不能为空");
        if (content.isBlank()) {
            throw new IllegalArgumentException("不允许创建空chunk");
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount不能小于0");
        }
        if ((pageFrom == null) != (pageTo == null)) {
            throw new IllegalArgumentException("pageFrom和pageTo必须同时为空或同时有值");
        }
        if (pageFrom != null && (pageFrom <= 0 || pageTo < pageFrom)) {
            throw new IllegalArgumentException("页码范围不合法");
        }
        sectionTitle = sectionTitle == null || sectionTitle.isBlank() ? null : sectionTitle;
        metadata = immutableMetadata(metadata);
    }

    /**
     * 内容摘要不由调用方提供，避免内容和摘要不一致。
     */
    public String contentHash() {
        return ContentHash.sha256(content);
    }

    private static Map<String, String> immutableMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        metadata.forEach((key, value) -> copied.put(
                Objects.requireNonNull(key, "metadata key不能为空"),
                Objects.requireNonNull(value, "metadata value不能为空")));
        return Collections.unmodifiableMap(copied);
    }
}
