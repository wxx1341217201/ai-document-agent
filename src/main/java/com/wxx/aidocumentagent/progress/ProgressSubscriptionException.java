package com.wxx.aidocumentagent.progress;

public class ProgressSubscriptionException extends RuntimeException {
    private final String code;

    public ProgressSubscriptionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
