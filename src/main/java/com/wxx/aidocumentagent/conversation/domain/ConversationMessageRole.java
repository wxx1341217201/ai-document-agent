package com.wxx.aidocumentagent.conversation.domain;

/** 可审计消息角色；TOOL 与 ERROR 仅允许保存结构化摘要，绝不保存隐藏推理。 */
public enum ConversationMessageRole {

    USER,
    ASSISTANT,
    TOOL,
    ERROR
}
