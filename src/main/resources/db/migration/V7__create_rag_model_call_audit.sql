-- M11: 仅保留可审计的模型调用指标；不保存提示词、回答、隐藏思维链、下游异常正文或任何密钥。
CREATE TABLE rag_model_call_audit
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT       NOT NULL COMMENT '知识库边界',
    trace_id          VARCHAR(64)  NOT NULL COMMENT '请求追踪标识',
    model_name        VARCHAR(128) NOT NULL COMMENT '实际或配置的模型名',
    prompt_tokens     INT          NULL COMMENT '服务商返回的输入 token；未知时为空',
    completion_tokens INT          NULL COMMENT '服务商返回的输出 token；未知时为空',
    total_tokens      INT          NULL COMMENT '服务商返回的总 token；未知时为空',
    elapsed_millis    BIGINT       NOT NULL COMMENT 'ChatClient 调用耗时',
    outcome           VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILURE',
    error_code        VARCHAR(64)  NULL COMMENT '失败时的稳定安全错误码',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_rag_model_call_audit_knowledge_created (knowledge_base_id, created_at),
    KEY idx_rag_model_call_audit_trace_id (trace_id),
    CONSTRAINT ck_rag_model_call_audit_tokens_non_negative CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
        AND elapsed_millis >= 0
    ),
    CONSTRAINT ck_rag_model_call_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT fk_rag_model_call_audit_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'RAG 模型调用审计指标';
