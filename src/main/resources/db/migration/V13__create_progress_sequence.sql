-- M18: notification sequence is durable per scoped execution or document ingestion job.
CREATE TABLE progress_sequence
(
    resource_type      VARCHAR(16) NOT NULL,
    execution_id       VARCHAR(36) NOT NULL,
    knowledge_base_id  BIGINT      NOT NULL,
    sequence_no        BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (resource_type, execution_id, knowledge_base_id),
    CONSTRAINT ck_progress_resource CHECK (resource_type IN ('EXECUTION', 'DOCUMENT')),
    CONSTRAINT ck_progress_sequence CHECK (sequence_no >= 0),
    CONSTRAINT fk_progress_knowledge_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
