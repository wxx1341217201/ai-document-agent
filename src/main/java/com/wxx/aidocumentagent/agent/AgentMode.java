package com.wxx.aidocumentagent.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.wxx.aidocumentagent.common.api.BusinessException;

/** 客户端可选的模式白名单；不接受类名或大小写模糊匹配。 */
public enum AgentMode {

    RETRIEVAL_FIRST,
    REACT,
    PLAN_EXECUTE_REFLECT;

    @JsonCreator
    public static AgentMode fromClientValue(String value) {
        if (value == null) {
            return RETRIEVAL_FIRST;
        }
        for (AgentMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        throw new BusinessException(AgentErrorCode.INVALID_MODE);
    }
}
