-- M07: existing M06 batch rows now represent the concrete vector-index stage.
-- Keep the old enum value in Java only long enough to consume messages already published before this migration.
UPDATE document_batch_task
SET stage = 'VECTOR_INDEX'
WHERE stage = 'INDEX';
