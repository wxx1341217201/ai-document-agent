package com.wxx.aidocumentagent.rag.application;

/** 将模型适配器错误归一为安全类别；真实下游异常仅保留为 cause，不回显给调用方或审计表。 */
public final class RagChatException extends RuntimeException {

    public enum Reason {
        TIMEOUT,
        CALL_FAILED,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public RagChatException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
