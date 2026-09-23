package com.wxx.aidocumentagent.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可直接提供给后续问答模块的、已融合且可追溯的 chunk。
 * 排名是各检索通道中的一基排名；未命中的通道以 null 表示，不能用 0 伪造命中。
 */
public record RetrievedChunk(
        long chunkId,
        long knowledgeBaseId,
        long documentId,
        String content,
        Integer pageFrom,
        Integer pageTo,
        Integer vectorRank,
        Double vectorScore,
        Integer keywordRank,
        Double keywordScore,
        double fusedScore,
        List<RetrievalChannel> hitChannels) {

    /** 来源文档的稳定主键；保留 documentId 组件以便与现有索引端口直接映射。 */
    public long sourceDocumentId() {
        return documentId;
    }

    public RetrievedChunk {
        if (chunkId <= 0 || knowledgeBaseId <= 0 || documentId <= 0) {
            throw new IllegalArgumentException("chunk、知识库和来源文档标识必须大于0");
        }
        content = Objects.requireNonNull(content, "chunk内容不能为空");
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("chunk页码范围不合法");
        }
        validateChannelFields(vectorRank, vectorScore, "vector");
        validateChannelFields(keywordRank, keywordScore, "keyword");
        if (!Double.isFinite(fusedScore) || fusedScore < 0D) {
            throw new IllegalArgumentException("融合分数必须是非负有限值");
        }
        hitChannels = orderedChannels(hitChannels);
        if (hitChannels.contains(RetrievalChannel.VECTOR) != (vectorRank != null)
                || hitChannels.contains(RetrievalChannel.KEYWORD) != (keywordRank != null)) {
            throw new IllegalArgumentException("命中通道与通道排名不一致");
        }
    }

    private static void validateChannelFields(Integer rank, Double score, String channel) {
        if ((rank == null) != (score == null) || (rank != null && rank < 1)) {
            throw new IllegalArgumentException(channel + "通道排名或分数不合法");
        }
        if (score != null && !Double.isFinite(score)) {
            throw new IllegalArgumentException(channel + "通道分数必须是有限值");
        }
    }

    private static List<RetrievalChannel> orderedChannels(List<RetrievalChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个命中通道");
        }
        if (channels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("命中通道不能为空");
        }
        List<RetrievalChannel> ordered = new ArrayList<>(2);
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            if (channels.contains(channel)) {
                ordered.add(channel);
            }
        }
        if (ordered.size() != channels.size()) {
            throw new IllegalArgumentException("命中通道不能重复");
        }
        return List.copyOf(ordered);
    }
}
