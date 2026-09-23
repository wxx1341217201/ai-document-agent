package com.wxx.aidocumentagent.vector.application;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingRequest;
import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VectorChunkBatchIndexingProcessorTest {

    @Test
    void 将受限batch映射为带完整追溯payload的chunkVector并单次交给索引端口() {
        VectorIndex vectorIndex = mock(VectorIndex.class);
        VectorProperties properties = new VectorProperties();
        properties.getEmbedding().setModel("fake-embedding-v1");
        VectorChunkBatchIndexingProcessor processor = new VectorChunkBatchIndexingProcessor(vectorIndex, properties);
        ChunkBatchIndexingRequest request = new ChunkBatchIndexingRequest("batch-1", "job-1", 9L, 101L, 0, 1,
                List.of(new ChunkBatchIndexingRequest.IndexableChunk(1001L, 0, "first", "a".repeat(64), 3,
                                5, 5, "intro", Map.of()),
                        new ChunkBatchIndexingRequest.IndexableChunk(1002L, 1, "second", "b".repeat(64), 3,
                                null, null, null, Map.of())));

        processor.process(request);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<ChunkVector>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorIndex).upsert(captor.capture());
        assertThat(captor.getValue()).hasSize(2).allSatisfy(vector -> {
            assertThat(vector.knowledgeBaseId()).isEqualTo(9L);
            assertThat(vector.documentId()).isEqualTo(101L);
            assertThat(vector.embeddingModel()).isEqualTo("fake-embedding-v1");
        });
        assertThat(captor.getValue().getFirst().payload()).containsEntry(ChunkVector.CHUNK_ID, "1001")
                .containsEntry(ChunkVector.PAGE_FROM, 5);
    }
}
