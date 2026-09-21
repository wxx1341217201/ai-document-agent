ALTER TABLE document
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';

CREATE TABLE document_ingestion_job
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    job_id                CHAR(36)     NOT NULL COMMENT '对外任务 UUID',
    document_id           BIGINT       NOT NULL COMMENT '文档',
    knowledge_base_id     BIGINT       NOT NULL COMMENT '知识库边界',
    operation             VARCHAR(32)  NOT NULL COMMENT 'PARSE_AND_SPLIT',
    status                VARCHAR(32)  NOT NULL COMMENT 'UPLOADED/QUEUED/PROCESSING/RETRYING/READY/FAILED',
    attempt               INT          NOT NULL DEFAULT 0 COMMENT '已进入的消息重试次数',
    total_batch_count     INT          NOT NULL DEFAULT 0 COMMENT '应完成 batch 总数',
    completed_batch_count INT          NOT NULL DEFAULT 0 COMMENT '已完成 batch 数',
    failed_batch_count    INT          NOT NULL DEFAULT 0 COMMENT '失败 batch 数',
    error_code            VARCHAR(64)  NULL COMMENT '安全错误码',
    error_message         VARCHAR(512) NULL COMMENT '安全错误摘要',
    enqueued_at           DATETIME(3)  NULL COMMENT '首次确认进入主队列时间',
    started_at            DATETIME(3)  NULL COMMENT '首次开始处理时间',
    completed_at          DATETIME(3)  NULL COMMENT '最终完成或失败时间',
    version               BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_ingestion_job_job_id (job_id),
    UNIQUE KEY uk_document_ingestion_job_document_operation (document_id, operation),
    KEY idx_document_ingestion_job_knowledge_status (knowledge_base_id, status),
    CONSTRAINT ck_document_ingestion_job_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT ck_document_ingestion_job_batch_counts_non_negative CHECK (
        total_batch_count >= 0 AND completed_batch_count >= 0 AND failed_batch_count >= 0
    ),
    CONSTRAINT fk_document_ingestion_job_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_document_ingestion_job_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档异步摄取总任务';

CREATE TABLE document_batch_task
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id          CHAR(36)     NOT NULL COMMENT '对外 batch UUID',
    job_id            CHAR(36)     NOT NULL COMMENT '所属摄取任务 UUID',
    document_id       BIGINT       NOT NULL COMMENT '文档',
    knowledge_base_id BIGINT       NOT NULL COMMENT '知识库边界',
    batch_no          INT          NOT NULL COMMENT '从0开始的稳定序号',
    chunk_from        INT          NOT NULL COMMENT '闭区间起始 chunk index',
    chunk_to          INT          NOT NULL COMMENT '闭区间结束 chunk index',
    stage             VARCHAR(32)  NOT NULL COMMENT 'INDEX',
    status            VARCHAR(32)  NOT NULL COMMENT 'PENDING_DISPATCH/QUEUED/PROCESSING/RETRYING/COMPLETED/FAILED',
    attempt           INT          NOT NULL DEFAULT 0 COMMENT '已进入的消息重试次数',
    enqueued_at       DATETIME(3)  NULL COMMENT '首次确认进入主队列时间',
    started_at        DATETIME(3)  NULL COMMENT '首次开始处理时间',
    completed_at      DATETIME(3)  NULL COMMENT '最终完成或失败时间',
    error_code        VARCHAR(64)  NULL COMMENT '安全错误码',
    error_message     VARCHAR(512) NULL COMMENT '安全错误摘要',
    version           BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_batch_task_batch_id (batch_id),
    UNIQUE KEY uk_document_batch_task_job_batch_no (job_id, batch_no),
    KEY idx_document_batch_task_knowledge_status (knowledge_base_id, status),
    KEY idx_document_batch_task_job_status (job_id, status),
    CONSTRAINT ck_document_batch_task_range CHECK (chunk_from >= 0 AND chunk_to >= chunk_from),
    CONSTRAINT ck_document_batch_task_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT fk_document_batch_task_job
        FOREIGN KEY (job_id) REFERENCES document_ingestion_job (job_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_batch_task_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_document_batch_task_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '长文档 chunk batch 异步任务';

CREATE TABLE document_ingestion_outbox_event
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id           CHAR(36)     NOT NULL COMMENT '消息事件 UUID',
    message_type       VARCHAR(32)  NOT NULL COMMENT 'DOCUMENT_COORDINATOR/CHUNK_BATCH',
    dispatch_mode      VARCHAR(16)  NOT NULL COMMENT 'PRIMARY/RETRY',
    job_id             CHAR(36)     NOT NULL COMMENT '摄取任务 UUID',
    batch_id           CHAR(36)     NULL COMMENT 'batch UUID',
    document_id        BIGINT       NOT NULL COMMENT '文档',
    knowledge_base_id  BIGINT       NOT NULL COMMENT '知识库边界',
    message_attempt    INT          NOT NULL DEFAULT 0 COMMENT '写入消息契约的 attempt',
    dispatch_attempt   INT          NOT NULL DEFAULT 0 COMMENT '发布确认重试计数',
    retry_delay_millis BIGINT       NOT NULL DEFAULT 0 COMMENT 'RETRY 队列单消息 TTL',
    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING/DISPATCHING/PUBLISHED/FAILED',
    available_at       DATETIME(3)  NOT NULL COMMENT '允许 outbox 调度的时间',
    locked_at          DATETIME(3)  NULL COMMENT '调度租约开始时间',
    published_at       DATETIME(3)  NULL COMMENT 'Broker confirm 成功时间',
    error_code         VARCHAR(64)  NULL COMMENT '最后一次安全错误码',
    error_message      VARCHAR(512) NULL COMMENT '最后一次安全错误摘要',
    version            BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_ingestion_outbox_event_id (event_id),
    KEY idx_document_ingestion_outbox_dispatch (status, available_at),
    KEY idx_document_ingestion_outbox_job (job_id),
    CONSTRAINT ck_document_ingestion_outbox_attempt_non_negative CHECK (
        message_attempt >= 0 AND dispatch_attempt >= 0 AND retry_delay_millis >= 0
    ),
    CONSTRAINT fk_document_ingestion_outbox_job
        FOREIGN KEY (job_id) REFERENCES document_ingestion_job (job_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_ingestion_outbox_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_document_ingestion_outbox_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '摄取消息事务 outbox';
