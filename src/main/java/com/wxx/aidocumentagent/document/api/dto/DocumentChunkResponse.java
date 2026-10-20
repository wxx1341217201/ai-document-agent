package com.wxx.aidocumentagent.document.api.dto;

public record DocumentChunkResponse(Long chunkId, int chunkIndex, String content,
                                     Integer pageFrom, Integer pageTo, String sectionTitle) {
}
