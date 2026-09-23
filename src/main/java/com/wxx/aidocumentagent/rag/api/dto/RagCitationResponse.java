package com.wxx.aidocumentagent.rag.api.dto;

/** 程序从本次已核验的 chunk 生成的机器可校验引用，绝不直接采用模型返回的元数据。 */
public record RagCitationResponse(
        String citationId,
        long documentId,
        String documentName,
        long chunkId,
        Integer pageFrom,
        Integer pageTo,
        String quote) {
}
