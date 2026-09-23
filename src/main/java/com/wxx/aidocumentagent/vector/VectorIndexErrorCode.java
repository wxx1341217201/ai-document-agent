package com.wxx.aidocumentagent.vector;

/** 可安全持久化到摄取任务的向量阶段故障分类。 */
public enum VectorIndexErrorCode {
    EMBEDDING_TIMEOUT("EMBEDDING_TIMEOUT", "嵌入模型请求超时", true),
    EMBEDDING_RATE_LIMITED("EMBEDDING_RATE_LIMITED", "嵌入模型限流", true),
    EMBEDDING_AUTHENTICATION_FAILED("EMBEDDING_AUTHENTICATION_FAILED", "嵌入模型鉴权失败", false),
    VECTOR_DIMENSION_MISMATCH("VECTOR_DIMENSION_MISMATCH", "向量维度与配置不匹配", false),
    VECTOR_COLLECTION_CONFIGURATION_MISMATCH("VECTOR_COLLECTION_CONFIGURATION_MISMATCH", "Qdrant collection向量配置不匹配", false),
    VECTOR_COLLECTION_NOT_FOUND("VECTOR_COLLECTION_NOT_FOUND", "Qdrant collection不存在", false),
    QDRANT_TIMEOUT("QDRANT_TIMEOUT", "Qdrant请求超时", true),
    QDRANT_AUTHENTICATION_FAILED("QDRANT_AUTHENTICATION_FAILED", "Qdrant鉴权失败", false),
    QDRANT_UNAVAILABLE("QDRANT_UNAVAILABLE", "Qdrant暂时不可用", true),
    EMBEDDING_REQUEST_FAILED("EMBEDDING_REQUEST_FAILED", "嵌入模型请求失败", false),
    QDRANT_OPERATION_FAILED("QDRANT_OPERATION_FAILED", "Qdrant向量操作失败", false);

    private final String code;
    private final String defaultMessage;
    private final boolean retryable;

    VectorIndexErrorCode(String code, String defaultMessage, boolean retryable) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
