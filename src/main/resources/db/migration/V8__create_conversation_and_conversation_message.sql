-- M12: 会话只关联一个知识库；消息仅保存用户输入、最终回答、引用与模型元数据，绝不保存隐藏推理。
CREATE TABLE conversation
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id     BIGINT       NOT NULL COMMENT '固定所属知识库',
    title                 VARCHAR(128) NOT NULL COMMENT '会话标题',
    agent_mode            VARCHAR(32)  NOT NULL COMMENT 'RAG',
    last_message_sequence BIGINT       NOT NULL DEFAULT 0 COMMENT '已分配的最后消息序号',
    version               BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_conversation_knowledge_updated (knowledge_base_id, updated_at, id),
    CONSTRAINT ck_conversation_agent_mode CHECK (agent_mode IN ('RAG')),
    CONSTRAINT ck_conversation_last_message_sequence CHECK (last_message_sequence >= 0),
    CONSTRAINT fk_conversation_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库会话';

CREATE TABLE conversation_message
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id   BIGINT       NOT NULL COMMENT '所属会话',
    sequence_no       BIGINT       NOT NULL COMMENT '同一会话内稳定顺序',
    role              VARCHAR(16)  NOT NULL COMMENT 'USER/ASSISTANT/TOOL/ERROR',
    content           MEDIUMTEXT   NOT NULL COMMENT '用户输入、最终回答或结构化工具摘要；不含隐藏推理',
    citations_json    JSON         NULL COMMENT '最终回答的已核验引用',
    model_name        VARCHAR(128) NULL COMMENT '实际模型名；未调用模型时为空',
    token_usage_json  JSON         NULL COMMENT '服务商返回的 token 使用量；未知时为空',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_message_sequence (conversation_id, sequence_no),
    KEY idx_conversation_message_conversation_sequence (conversation_id, sequence_no, id),
    CONSTRAINT ck_conversation_message_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'ERROR')),
    CONSTRAINT fk_conversation_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库会话消息';
