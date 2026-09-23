package com.wxx.aidocumentagent.vector;

/** 启动时对 Qdrant collection 的显式处理策略。 */
public enum VectorInitializationStrategy {
    CREATE_IF_MISSING,
    VALIDATE_EXISTING
}
