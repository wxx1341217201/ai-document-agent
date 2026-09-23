package com.wxx.aidocumentagent.keyword.application;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingRequest;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KeywordChunkBatchIndexingProcessorTest {

    @Test
    void 将受限batch映射为稳定chunkId和可检索标题字段() {
        KeywordIndex keywordIndex = mock(KeywordIndex.class);
        KeywordChunkBatchIndexingProcessor processor = new KeywordChunkBatchIndexingProcessor(keywordIndex);
        ChunkBatchIndexingRequest request = new ChunkBatchIndexingRequest("batch-1", "job-1", 9L, 101L, 0, 1,
                "操作手册.pdf", List.of(
                        new ChunkBatchIndexingRequest.IndexableChunk(1001L, 0, "错误码 ERR-1042", "a".repeat(64), 3,
                                5, 5, "故障排查", Map.of()),
                        new ChunkBatchIndexingRequest.IndexableChunk(1002L, 1, "向量检索", "b".repeat(64), 3,
                                null, null, null, Map.of())));

        processor.process(request);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<KeywordDocument>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(keywordIndex).upsert(captor.capture());
        assertThat(processor.stage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);
        assertThat(captor.getValue()).extracting(KeywordDocument::elasticsearchId).containsExactly("1001", "1002");
        assertThat(captor.getValue().getFirst()).satisfies(document -> {
            assertThat(document.knowledgeBaseId()).isEqualTo(9L);
            assertThat(document.documentId()).isEqualTo(101L);
            assertThat(document.title()).isEqualTo("操作手册.pdf");
            assertThat(document.sectionTitle()).isEqualTo("故障排查");
        });
    }
}
