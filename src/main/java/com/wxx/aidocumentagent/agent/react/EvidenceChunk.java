package com.wxx.aidocumentagent.agent.react;

/** 正文仅用于当前模型轮次；持久化只写引用和步骤摘要。 */
public record EvidenceChunk(long knowledgeBaseId, long chunkId, long documentId, String documentName,
                            Integer pageFrom, Integer pageTo, String text, String sourceContentSha256) {
    public EvidenceChunk(long knowledgeBaseId, long chunkId, long documentId, String documentName,
                         Integer pageFrom, Integer pageTo, String text) {
        this(knowledgeBaseId, chunkId, documentId, documentName, pageFrom, pageTo, text, null);
    }
}
