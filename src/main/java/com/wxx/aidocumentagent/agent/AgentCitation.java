package com.wxx.aidocumentagent.agent;

import java.util.Objects;

/** 引用显式携带所属知识库，以便路由层再次核验执行结果的边界。 */
public record AgentCitation(long knowledgeBaseId, String citationId, long documentId, String documentName,
                            long chunkId, Integer pageFrom, Integer pageTo, String quote) {

    public AgentCitation {
        if (knowledgeBaseId <= 0L || documentId <= 0L || chunkId <= 0L) {
            throw new IllegalArgumentException("引用的知识库、文档或chunk标识不合法");
        }
        if (citationId == null || !citationId.matches("C[1-9]\\d*")) {
            throw new IllegalArgumentException("citationId不合法");
        }
        Objects.requireNonNull(documentName, "documentName不能为空");
        Objects.requireNonNull(quote, "quote不能为空");
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("引用页码范围不合法");
        }
    }
}
