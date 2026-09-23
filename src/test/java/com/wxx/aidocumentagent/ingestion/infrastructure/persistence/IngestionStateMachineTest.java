package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionStateMachineTest {

    @Test
    void 同一batch必须从向量合法推进到关键词再完成() {
        DocumentIngestionJob job = processingJob();
        DocumentBatchTask task = DocumentBatchTask.create(job, 0, 0, 49);
        task.markQueued(LocalDateTime.now());

        assertThat(task.getStage()).isEqualTo(ChunkBatchStage.VECTOR_INDEX);

        assertThat(task.beginProcessing(LocalDateTime.now())).isTrue();
        assertThat(task.beginProcessing(LocalDateTime.now())).isFalse();
        assertThatThrownBy(() -> task.markCompleted(LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
        task.advanceToKeywordIndex();
        assertThat(task.getStage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);
        assertThat(task.getStatus()).isEqualTo(com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus.PENDING_DISPATCH);
        task.markQueued(LocalDateTime.now());
        assertThat(task.beginProcessing(LocalDateTime.now())).isTrue();
        task.markCompleted(LocalDateTime.now());

        assertThat(task.beginProcessing(LocalDateTime.now())).isFalse();
        assertThat(task.getStatus()).isEqualTo(com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus.COMPLETED);
    }

    @Test
    void 非法状态转换和未完成聚合都不能误报READY() {
        DocumentIngestionJob job = processingJob();
        job.defineBatches(2);
        job.updateBatchSummary(2, 1, 0);

        assertThatThrownBy(() -> job.markReady(LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DocumentBatchTask.create(job, 0, 0, 49).markCompleted(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 聚合全部完成后只允许一次最终READY转换() {
        DocumentIngestionJob job = processingJob();
        job.defineBatches(2);
        job.updateBatchSummary(2, 2, 0);
        job.markReady(LocalDateTime.now());

        assertThatThrownBy(() -> job.markReady(LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
    }

    private DocumentIngestionJob processingJob() {
        DocumentIngestionJob job = DocumentIngestionJob.create(9L, 101L);
        job.markQueued(LocalDateTime.now());
        job.beginProcessing(LocalDateTime.now());
        return job;
    }
}
