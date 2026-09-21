CREATE TABLE document_chunk
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'chunk 主键',
    knowledge_base_id BIGINT       NOT NULL COMMENT '冗余知识库边界',
    document_id       BIGINT       NOT NULL COMMENT '所属文档',
    chunk_index       INT          NOT NULL COMMENT '文档内稳定顺序，从 0 开始',
    content           MEDIUMTEXT   NOT NULL COMMENT 'chunk 文本内容',
    content_hash      CHAR(64)     NOT NULL COMMENT 'chunk 文本 SHA-256',
    token_count       INT          NOT NULL COMMENT '估算 token 数',
    page_from         INT          NULL COMMENT '起始页码',
    page_to           INT          NULL COMMENT '结束页码',
    section_title     VARCHAR(512) NULL COMMENT '最近章节标题',
    metadata_json     JSON         NOT NULL COMMENT '扩展且可追溯的元数据',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_chunk_document_index (document_id, chunk_index),
    KEY idx_document_chunk_knowledge_document_order (knowledge_base_id, document_id, chunk_index),
    KEY idx_document_chunk_knowledge_base_id (knowledge_base_id),
    CONSTRAINT ck_document_chunk_index_non_negative CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunk_token_count_non_negative CHECK (token_count >= 0),
    CONSTRAINT ck_document_chunk_page_range CHECK (
        (page_from IS NULL AND page_to IS NULL)
        OR (page_from >= 1 AND page_to >= page_from)
    ),
    CONSTRAINT fk_document_chunk_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_document_chunk_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档可索引文本块';
