package com.wxx.aidocumentagent.agent;

import java.util.Map;

/** 请求独占的诊断钩子；普通请求使用 NO_OP，HTTP 参数不能启用故障注入。 */
public interface AgentExecutionProbe {
    AgentExecutionProbe NO_OP = new AgentExecutionProbe() { };

    default void evidence(AgentCitation citation, String contextText) { }
    default void step(AgentStepSummary summary) { }
    default void tool(AgentToolCallSummary summary) { }
    default void toolResult(String toolName, Map<String, String> resultSummary) { }
    /** Detailed diagnostics are consumed only by an explicitly installed local probe. No production log output. */
    default void diagnostic(String stage, Map<String, Object> details) { }

    /** 只在白名单、参数与幂等校验通过后调用。不接受原始工具参数或模型推理。 */
    default ToolAction beforeTool(long knowledgeBaseId, String toolName, Map<String, String> summary) {
        return ToolAction.RUN;
    }

    enum ToolAction { RUN, FAIL, EMPTY_EVIDENCE }
}
