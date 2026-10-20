package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.infrastructure.DocumentChunksIndexInitializer;
import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.wxx.aidocumentagent.retrieval.benchmark.RetrievalBenchmarkCorpus.Chunk;

/** Explicit, idempotent preparation of isolated benchmark indexes from scoped persisted chunks. */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.retrieval", name = "enabled", havingValue = "true")
public final class RetrievalBenchmarkCorpusBootstrapper {

    private static final String CHUNKS_SQL = """
            SELECT c.id, c.knowledge_base_id, c.document_id, c.chunk_index, c.content,
                   c.content_hash, c.page_from, c.page_to, c.section_title, d.original_name
            FROM document_chunk c
            JOIN document d ON d.id = c.document_id AND d.knowledge_base_id = c.knowledge_base_id
            WHERE c.knowledge_base_id = ? AND d.knowledge_base_id = ?
            ORDER BY c.document_id, c.chunk_index
            """;

    private final JdbcTemplate jdbc;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final VectorProperties vectorProperties;
    private final DocumentChunksIndexInitializer keywordInitializer;
    private final ElasticsearchClient elasticsearch;
    private final Environment environment;

    public RetrievalBenchmarkCorpusBootstrapper(JdbcTemplate jdbc, VectorIndex vectorIndex, KeywordIndex keywordIndex,
                                                VectorProperties vectorProperties,
                                                DocumentChunksIndexInitializer keywordInitializer,
                                                ElasticsearchClient elasticsearch, Environment environment) {
        this.jdbc = jdbc;
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.vectorProperties = vectorProperties;
        this.keywordInitializer = keywordInitializer;
        this.elasticsearch = elasticsearch;
        this.environment = environment;
    }

    public int bootstrap(long knowledgeBaseId) throws IOException {
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId must be positive");
        }
        benchmarkName("app.vector.qdrant.collection-name");
        benchmarkName("app.keyword.index-name");
        benchmarkName("app.keyword.alias");
        return bootstrap(readCorpus(knowledgeBaseId));
    }

    public RetrievalBenchmarkCorpus readCorpus(long knowledgeBaseId) throws IOException {
        String frozenCorpus = environment.getProperty("app.benchmark.retrieval.corpus", "");
        if (!frozenCorpus.isBlank()) {
            return RetrievalBenchmarkCorpus.load(Path.of(frozenCorpus), new JsonMapper());
        }
        List<Chunk> chunks = new ArrayList<>(jdbc.query(CHUNKS_SQL, this::mapChunk, knowledgeBaseId, knowledgeBaseId));
        String supplement = environment.getProperty("app.benchmark.retrieval.supplemental-corpus", "");
        if (!supplement.isBlank()) {
            chunks.addAll(RetrievalBenchmarkCorpus.load(Path.of(supplement), new JsonMapper()).chunks());
        }
        if (chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk.knowledgeBaseId() != knowledgeBaseId)) {
            throw new IllegalStateException("Benchmark corpus is empty or crosses knowledge base boundary");
        }
        return new RetrievalBenchmarkCorpus(chunks);
    }

    public int bootstrap(RetrievalBenchmarkCorpus corpus) throws IOException {
        benchmarkName("app.vector.qdrant.collection-name");
        benchmarkName("app.keyword.index-name");
        String alias = benchmarkName("app.keyword.alias");
        List<Chunk> chunks = corpus.chunks();

        keywordInitializer.initialize();
        int batchSize = vectorProperties.getEmbedding().getBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Chunk> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            vectorIndex.upsert(batch.stream().map(chunk -> new ChunkVector(chunk.chunkId(), chunk.documentId(),
                    chunk.knowledgeBaseId(), chunk.chunkIndex(), chunk.pageFrom(), chunk.pageTo(),
                    chunk.contentHash(), vectorProperties.getEmbedding().getModel(), chunk.content())).toList());
            keywordIndex.upsert(batch.stream().map(chunk -> new KeywordDocument(chunk.chunkId(), chunk.knowledgeBaseId(),
                    chunk.documentId(), chunk.content(), chunk.title(), chunk.sectionTitle(), chunk.pageFrom(),
                    chunk.pageTo(), chunk.contentHash())).toList());
        }
        elasticsearch.indices().refresh(request -> request.index(alias));
        return chunks.size();
    }

    private String benchmarkName(String property) {
        String value = environment.getProperty(property, "");
        if (!value.toLowerCase(Locale.ROOT).contains("benchmark")) {
            throw new IllegalArgumentException(property + " must name an isolated benchmark index");
        }
        return value;
    }

    private Chunk mapChunk(ResultSet row, int index) throws SQLException {
        return new Chunk(row.getLong("id"), row.getLong("knowledge_base_id"), row.getLong("document_id"),
                row.getInt("chunk_index"), row.getString("content"), row.getString("original_name"),
                row.getString("section_title"), row.getObject("page_from", Integer.class),
                row.getObject("page_to", Integer.class), "mysql:document:" + row.getLong("document_id"));
    }
}
