package com.wxx.aidocumentagent.agent;

/** 所有执行范式共用的入口；实现负责在外部调用前检查上下文的剩余时间与取消状态。 */
public interface DocumentAgent {

    AgentMode mode();

    AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
