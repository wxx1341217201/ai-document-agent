package com.wxx.aidocumentagent.vector.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorHit;
import com.wxx.aidocumentagent.vector.VectorProperties;
import com.wxx.aidocumentagent.vector.VectorQuery;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantVectorIndexTest {

    @Test
    void 批量upsert使用稳定pointId且确定性fakeEmbedding不产生重复point() {
        VectorProperties properties = properties();
        DeterministicFakeEmbeddingModel embeddingModel = new DeterministicFakeEmbeddingModel(4);
        RecordingVectorStore vectorStore = new RecordingVectorStore(embeddingModel);
        QdrantVectorIndex index = new QdrantVectorIndex(vectorStore, properties, new VectorIndexExceptionTranslator());
        List<ChunkVector> vectors = vectors();

        index.upsert(vectors);
        index.upsert(vectors);

        assertThat(vectorStore.points).hasSize(3);
        assertThat(vectorStore.addBatchSizes).containsExactly(2, 1, 2, 1);
        assertThat(embeddingModel.batchSizes).containsExactly(2, 1, 2, 1);
        Document firstPoint = vectorStore.points.get(vectors.getFirst().pointId());
        assertThat(firstPoint).isNotNull();
        assertThat(firstPoint.getMetadata()).containsEntry(ChunkVector.CHUNK_ID, "1001")
                .containsEntry(ChunkVector.DOCUMENT_ID, "101")
                .containsEntry(ChunkVector.KNOWLEDGE_BASE_ID, "9")
                .containsEntry(ChunkVector.CHUNK_INDEX, 0)
                .containsEntry(ChunkVector.PAGE_FROM, 5)
                .containsEntry(ChunkVector.PAGE_TO, 6)
                .containsEntry(ChunkVector.CONTENT_HASH, "a".repeat(64))
                .containsEntry(ChunkVector.EMBEDDING_MODEL, "fake-embedding-v1");
        assertThat(firstPoint.getId()).isEqualTo(vectors.getFirst().pointId());
    }

    @Test
    void 搜索强制带知识库过滤且二次过滤意外跨库结果() {
        VectorProperties properties = properties();
        RecordingVectorStore vectorStore = new RecordingVectorStore(new DeterministicFakeEmbeddingModel(4));
        QdrantVectorIndex index = new QdrantVectorIndex(vectorStore, properties, new VectorIndexExceptionTranslator());
        ChunkVector own = vectors().getFirst();
        ChunkVector foreign = new ChunkVector(2001L, 202L, 10L, 0, null, null, "d".repeat(64),
                "fake-embedding-v1", "other knowledge base");
        vectorStore.searchResults = List.of(toDocument(own, 0.91D), toDocument(foreign, 0.99D));

        List<VectorHit> hits = index.search(new VectorQuery(9L, "检索文本", 5, 0.2D));

        assertThat(vectorStore.lastSearchRequest).isNotNull();
        assertThat(vectorStore.lastSearchRequest.hasFilterExpression()).isTrue();
        assertThat(vectorStore.lastSearchRequest.getFilterExpression().toString()).contains(ChunkVector.KNOWLEDGE_BASE_ID)
                .contains("9");
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.knowledgeBaseId()).isEqualTo(9L);
            assertThat(hit.chunkId()).isEqualTo(1001L);
            assertThat(hit.score()).isEqualTo(0.91D);
        });
    }

    @Test
    void 按文档删除同时带知识库和文档边界() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(new DeterministicFakeEmbeddingModel(4));
        QdrantVectorIndex index = new QdrantVectorIndex(vectorStore, properties(), new VectorIndexExceptionTranslator());

        index.deleteByDocument(9L, 101L);

        assertThat(vectorStore.lastDeleteFilter).isNotNull();
        assertThat(vectorStore.lastDeleteFilter.toString()).contains(ChunkVector.KNOWLEDGE_BASE_ID)
                .contains(ChunkVector.DOCUMENT_ID).contains("9").contains("101");
    }

    private VectorProperties properties() {
        VectorProperties properties = new VectorProperties();
        properties.getEmbedding().setModel("fake-embedding-v1");
        properties.getEmbedding().setDimensions(4);
        properties.getEmbedding().setBatchSize(2);
        return properties;
    }

    private List<ChunkVector> vectors() {
        return List.of(
                new ChunkVector(1001L, 101L, 9L, 0, 5, 6, "a".repeat(64), "fake-embedding-v1", "first chunk"),
                new ChunkVector(1002L, 101L, 9L, 1, 6, 6, "b".repeat(64), "fake-embedding-v1", "second chunk"),
                new ChunkVector(1003L, 101L, 9L, 2, null, null, "c".repeat(64), "fake-embedding-v1", "third chunk"));
    }

    private static Document toDocument(ChunkVector vector, double score) {
        return Document.builder().id(vector.pointId()).text(vector.content()).metadata(vector.payload()).score(score).build();
    }

    /** 测试专用的确定性 fake；它不调用任何模型服务，也不声称产生真实语义效果。 */
    private static final class DeterministicFakeEmbeddingModel implements EmbeddingModel {

        private final int dimensions;
        private final List<Integer> batchSizes = new ArrayList<>();

        private DeterministicFakeEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("test fake只通过embed方法生成确定性向量");
        }

        @Override
        public float[] embed(String text) {
            return deterministicVector(text);
        }

        @Override
        public float[] embed(Document document) {
            return deterministicVector(document.getText());
        }

        @Override
        public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
            batchSizes.add(documents.size());
            return documents.stream().map(Document::getText).map(this::deterministicVector).toList();
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] deterministicVector(String text) {
            int seed = text.hashCode();
            float[] vector = new float[dimensions];
            for (int index = 0; index < dimensions; index++) {
                vector[index] = ((seed >>> (index * 8)) & 0xFF) / 255F;
            }
            return vector;
        }
    }

    private static final class RecordingVectorStore implements VectorStore {

        private final DeterministicFakeEmbeddingModel embeddingModel;
        private final Map<String, Document> points = new LinkedHashMap<>();
        private final List<Integer> addBatchSizes = new ArrayList<>();
        private List<Document> searchResults = List.of();
        private SearchRequest lastSearchRequest;
        private Filter.Expression lastDeleteFilter;

        private RecordingVectorStore(DeterministicFakeEmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        @Override
        public void add(List<Document> documents) {
            addBatchSizes.add(documents.size());
            embeddingModel.embed(documents, null, null);
            documents.forEach(document -> points.put(document.getId(), document));
        }

        @Override
        public void delete(List<String> documentIds) {
            documentIds.forEach(points::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            lastDeleteFilter = filterExpression;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            lastSearchRequest = request;
            return searchResults;
        }
    }
}
