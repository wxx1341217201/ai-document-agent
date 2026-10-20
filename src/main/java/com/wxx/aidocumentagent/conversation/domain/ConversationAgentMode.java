package com.wxx.aidocumentagent.conversation.domain;

/** 新会话默认使用检索优先；RAG 保留给既有会话。 */
public enum ConversationAgentMode {

    RAG,
    RETRIEVAL_FIRST,
    REACT,
    PLAN_EXECUTE_REFLECT
}
