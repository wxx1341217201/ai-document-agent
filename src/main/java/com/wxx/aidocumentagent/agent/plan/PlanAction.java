package com.wxx.aidocumentagent.agent.plan;

/** 模型只能选择这些操作，不能指定工具名或代码。 */
public enum PlanAction {
    SEARCH, FETCH_CONTEXT, SYNTHESIZE
}
