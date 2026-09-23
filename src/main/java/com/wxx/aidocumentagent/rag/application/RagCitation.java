package com.wxx.aidocumentagent.rag.application;

import java.util.Objects;

/** 与当前上下文一一对应的稳定引用；其元数据只能来自受知识库边界约束的持久化实体。 */
record RagCitation(
        String citationId,
        long documentId,
        String documentName,
        long chunkId,
        Integer pageFrom,
        Integer pageTo,
        String quote) {

    RagCitation {
        if (citationId == null || !citationId.matches("C[1-9]\\d*")) {
            throw new IllegalArgumentException("citationId不合法");
        }
        if (documentId <= 0L || chunkId <= 0L) {
            throw new IllegalArgumentException("引用文档或chunk标识不合法");
        }
        documentName = Objects.requireNonNull(documentName, "documentName不能为空");
        quote = Objects.requireNonNull(quote, "quote不能为空");
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("引用页码范围不合法");
        }
    }
}
