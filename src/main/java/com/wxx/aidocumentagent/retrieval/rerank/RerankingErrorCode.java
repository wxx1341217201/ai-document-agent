package com.wxx.aidocumentagent.retrieval.rerank;

/** 面向调用方的安全重排错误分类；不暴露供应商响应正文或认证信息。 */
public enum RerankingErrorCode {

    INVALID_REQUEST("重排请求不合法", false),
    REMOTE_UNAVAILABLE("重排服务暂时不可用", true),
    REQUEST_TIMEOUT("重排服务请求超时", true),
    REMOTE_REJECTED("重排服务拒绝请求", false),
    INVALID_RESPONSE("重排服务返回了无效结果", false),
    CIRCUIT_OPEN("重排服务熔断保护中", true),
    INTERNAL_FAILURE("重排处理失败", false);

    private final String defaultMessage;
    private final boolean retryable;

    RerankingErrorCode(String defaultMessage, boolean retryable) {
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
