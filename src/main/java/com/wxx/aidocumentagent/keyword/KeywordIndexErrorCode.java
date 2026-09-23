package com.wxx.aidocumentagent.keyword;

/** 可安全持久化到摄取任务的 Elasticsearch 关键词阶段错误码。 */
public enum KeywordIndexErrorCode {
    ELASTICSEARCH_TIMEOUT("ELASTICSEARCH_TIMEOUT", "Elasticsearch请求超时", true),
    ELASTICSEARCH_UNAVAILABLE("ELASTICSEARCH_UNAVAILABLE", "Elasticsearch暂时不可用", true),
    ELASTICSEARCH_REJECTED("ELASTICSEARCH_REJECTED", "Elasticsearch暂时拒绝请求", true),
    ELASTICSEARCH_AUTHENTICATION_FAILED("ELASTICSEARCH_AUTHENTICATION_FAILED", "Elasticsearch鉴权失败", false),
    ELASTICSEARCH_INDEX_CONFIGURATION_MISMATCH("ELASTICSEARCH_INDEX_CONFIGURATION_MISMATCH",
            "Elasticsearch索引或别名配置不匹配", false),
    ELASTICSEARCH_BULK_ITEM_FAILED("ELASTICSEARCH_BULK_ITEM_FAILED", "Elasticsearch批量索引项失败", false),
    ELASTICSEARCH_OPERATION_FAILED("ELASTICSEARCH_OPERATION_FAILED", "Elasticsearch关键词索引操作失败", false);

    private final String code;
    private final String defaultMessage;
    private final boolean retryable;

    KeywordIndexErrorCode(String code, String defaultMessage, boolean retryable) {
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
