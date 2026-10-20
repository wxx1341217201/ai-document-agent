package com.wxx.aidocumentagent.agent.react;

import java.util.Map;

/** 模型只能提交 input；知识库作用域和已授权证据由可信上下文提供。 */
public interface AgentTool<I, O> {

    String name();
    String description();
    Class<I> inputType();
    O execute(I input, ToolExecutionContext context);

    /** Durable executions admit only explicitly declared read-only tools. */
    default boolean readOnly() { return false; }

    /** 只能返回明确挑选的安全字段，不能放入查询文本或正文。 */
    default Map<String, String> summarize(I input) {
        return Map.of();
    }
}
