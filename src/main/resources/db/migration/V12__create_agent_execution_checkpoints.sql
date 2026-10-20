-- M17: Durable, knowledge-base scoped Agent executions. Only structured checkpoints and safe summaries are stored.
CREATE TABLE agent_execution
(
    execution_id       VARCHAR(36)  NOT NULL,
    request_key        VARCHAR(128) NOT NULL,
    request_hash       CHAR(64)     NOT NULL,
    knowledge_base_id  BIGINT       NOT NULL,
    conversation_id    BIGINT       NULL,
    agent_mode         VARCHAR(32)  NOT NULL,
    question           VARCHAR(2000) NOT NULL,
    parameters_json    JSON         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    current_step       VARCHAR(64)  NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    deadline_at        DATETIME(3)  NOT NULL,
    lease_until        DATETIME(3)  NULL,
    run_token          VARCHAR(36)  NULL,
    cancel_requested   BOOLEAN      NOT NULL DEFAULT FALSE,
    retryable          BOOLEAN      NOT NULL DEFAULT FALSE,
    result_json        JSON         NULL,
    error_code         VARCHAR(64)  NULL,
    started_at         DATETIME(3)  NULL,
    finished_at        DATETIME(3)  NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (execution_id),
    UNIQUE KEY uk_agent_execution_request_key (request_key),
    UNIQUE KEY uk_agent_execution_scope (execution_id, knowledge_base_id),
    KEY idx_agent_execution_knowledge (knowledge_base_id, created_at),
    KEY idx_agent_execution_recovery (status, lease_until),
    CONSTRAINT ck_agent_execution_status CHECK (status IN
        ('PENDING', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_agent_execution_mode CHECK (agent_mode IN
        ('RETRIEVAL_FIRST', 'REACT', 'PLAN_EXECUTE_REFLECT')),
    CONSTRAINT fk_agent_execution_knowledge_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_step_execution
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    execution_id       VARCHAR(36)  NOT NULL,
    knowledge_base_id  BIGINT       NOT NULL,
    step_id            VARCHAR(64)  NOT NULL,
    kind               VARCHAR(32)  NOT NULL,
    operation          VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    depends_on_json    JSON         NULL,
    error_code         VARCHAR(64)  NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_step_execution (execution_id, step_id),
    KEY idx_agent_step_knowledge (knowledge_base_id, execution_id),
    KEY idx_agent_step_scope (execution_id, knowledge_base_id),
    CONSTRAINT ck_agent_step_status CHECK (status IN
        ('PENDING', 'RUNNING', 'COMPLETED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT fk_agent_step_execution FOREIGN KEY (execution_id, knowledge_base_id)
        REFERENCES agent_execution (execution_id, knowledge_base_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE tool_invocation
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    execution_id        VARCHAR(36)  NOT NULL,
    knowledge_base_id   BIGINT       NOT NULL,
    invocation_key      VARCHAR(128) NOT NULL,
    tool_name           VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    arguments_summary_json JSON     NOT NULL,
    elapsed_millis      BIGINT       NULL,
    error_code          VARCHAR(64)  NULL,
    side_effecting      BOOLEAN      NOT NULL DEFAULT FALSE,
    idempotency_key     VARCHAR(128) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_invocation_key (execution_id, invocation_key),
    UNIQUE KEY uk_tool_invocation_idempotency (execution_id, idempotency_key),
    KEY idx_tool_invocation_knowledge (knowledge_base_id, execution_id),
    KEY idx_tool_invocation_scope (execution_id, knowledge_base_id),
    CONSTRAINT ck_tool_invocation_status CHECK (status IN
        ('PENDING', 'RUNNING', 'COMPLETED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_tool_invocation_elapsed CHECK (elapsed_millis IS NULL OR elapsed_millis >= 0),
    CONSTRAINT ck_tool_invocation_side_effect_key CHECK (side_effecting = FALSE OR idempotency_key IS NOT NULL),
    CONSTRAINT fk_tool_invocation_execution FOREIGN KEY (execution_id, knowledge_base_id)
        REFERENCES agent_execution (execution_id, knowledge_base_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
