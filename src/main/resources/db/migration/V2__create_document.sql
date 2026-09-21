CREATE TABLE document
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT       NOT NULL COMMENT '所属知识库',
    original_name     VARCHAR(255) NOT NULL COMMENT '用户原始文件名',
    storage_key       VARCHAR(128) NOT NULL COMMENT '服务端生成的存储键',
    content_type      VARCHAR(128) NOT NULL COMMENT '标准化 MIME 类型',
    extension         VARCHAR(16)  NOT NULL COMMENT '标准化扩展名',
    size_bytes        BIGINT       NOT NULL COMMENT '文件字节数',
    sha256            CHAR(64)     NOT NULL COMMENT '内容 SHA-256',
    status            VARCHAR(32)  NOT NULL COMMENT '文档生命周期状态',
    error_code        VARCHAR(64)  NULL COMMENT '处理失败错误码',
    error_message     VARCHAR(512) NULL COMMENT '安全错误摘要',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_document_knowledge_base_sha256 (knowledge_base_id, sha256),
    UNIQUE KEY uk_document_storage_key (storage_key),
    KEY idx_document_knowledge_base_id (knowledge_base_id),
    CONSTRAINT fk_document_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库文档';
