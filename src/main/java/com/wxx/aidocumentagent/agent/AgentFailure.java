package com.wxx.aidocumentagent.agent;

import java.util.Objects;

/** 可对外暴露的稳定错误，不携带异常正文或模型中间输出。 */
public record AgentFailure(AgentErrorCode code) {

    public AgentFailure {
        Objects.requireNonNull(code, "code不能为空");
    }

    public AgentErrorCode.Category category() {
        return code.category();
    }
}
