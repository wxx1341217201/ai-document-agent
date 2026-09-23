-- M08: 一个 batch 在同一持久化任务中依次执行 VECTOR_INDEX 与 KEYWORD_INDEX。
-- 保留 INDEX 仅用于安全消费 M06 已发布但尚未确认的旧消息。
ALTER TABLE document_batch_task
    ADD CONSTRAINT ck_document_batch_task_stage
        CHECK (stage IN ('INDEX', 'VECTOR_INDEX', 'KEYWORD_INDEX'));

-- 固化 outbox 产生时的阶段，不能在任务推进到 KEYWORD_INDEX 后把旧 VECTOR_INDEX 消息改写为新阶段。
ALTER TABLE document_ingestion_outbox_event
    ADD COLUMN batch_stage VARCHAR(32) NULL COMMENT 'CHUNK_BATCH产生时的索引阶段' AFTER batch_id;

UPDATE document_ingestion_outbox_event event
JOIN document_batch_task task
    ON task.batch_id = event.batch_id
    AND task.knowledge_base_id = event.knowledge_base_id
SET event.batch_stage = task.stage
WHERE event.message_type = 'CHUNK_BATCH'
  AND event.batch_stage IS NULL;

ALTER TABLE document_ingestion_outbox_event
    ADD CONSTRAINT ck_document_ingestion_outbox_batch_stage
        CHECK (
            (message_type = 'DOCUMENT_COORDINATOR' AND batch_id IS NULL AND batch_stage IS NULL)
            OR (message_type = 'CHUNK_BATCH' AND batch_id IS NOT NULL AND batch_stage IN ('INDEX', 'VECTOR_INDEX', 'KEYWORD_INDEX'))
        );
