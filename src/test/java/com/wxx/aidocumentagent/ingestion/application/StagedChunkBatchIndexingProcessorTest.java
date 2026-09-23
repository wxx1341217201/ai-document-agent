package com.wxx.aidocumentagent.ingestion.application;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StagedChunkBatchIndexingProcessorTest {

    @Test
    void 每次消费只调用持久化指定的一个阶段() {
        ChunkBatchStageProcessor vector = mock(ChunkBatchStageProcessor.class);
        ChunkBatchStageProcessor keyword = mock(ChunkBatchStageProcessor.class);
        when(vector.stage()).thenReturn(ChunkBatchStage.VECTOR_INDEX);
        when(keyword.stage()).thenReturn(ChunkBatchStage.KEYWORD_INDEX);
        StagedChunkBatchIndexingProcessor processor = new StagedChunkBatchIndexingProcessor(vector, keyword);
        ChunkBatchIndexingRequest request = new ChunkBatchIndexingRequest("batch", "job", 9L, 101L, 0, 0,
                List.of(new ChunkBatchIndexingRequest.IndexableChunk(1001L, 0, "ERR-1042", "a".repeat(64), 1,
                        null, null, null, Map.of())));

        processor.process(ChunkBatchStage.VECTOR_INDEX, request);

        verify(vector).process(request);
        verify(keyword, never()).process(request);
    }
}
