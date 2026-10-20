package com.wxx.aidocumentagent.ingestion.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingProcessor;
import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingRequest;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Deterministic local embeddings and a persisted keyword digest, identical in A and B. */
public class LocalBenchmarkIndex implements ChunkBatchIndexingProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int perChunkDelayMillis;
    private final int perBatchDelayMillis;

    public LocalBenchmarkIndex(JdbcTemplate jdbc, ObjectMapper mapper, int chunkDelay, int batchDelay) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        perChunkDelayMillis = chunkDelay;
        perBatchDelayMillis = batchDelay;
    }

    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS m19e_chunk_index (chunk_id BIGINT PRIMARY KEY, "
                + "vector_json TEXT NULL, keyword_digest CHAR(64) NULL)");
    }

    public static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static float[] embedding(String content) {
        byte[] bytes = HexFormat.of().parseHex(digest(content));
        float[] vector = new float[32];
        for (int i = 0; i < vector.length; i++) vector[i] = ((bytes[i] & 255) - 127.5f) / 127.5f;
        return vector;
    }

    @Override @Transactional
    public void process(ChunkBatchStage stage, ChunkBatchIndexingRequest request) {
        boolean vector = stage == ChunkBatchStage.VECTOR_INDEX;
        try {
            // A controlled blocking model cost, not a measured external-provider response time.
            Thread.sleep(perBatchDelayMillis + (vector ? (long) perChunkDelayMillis * request.chunks().size() : 0));
            var values = new ArrayList<Object[]>();
            for (var chunk : request.chunks()) values.add(new Object[] {chunk.id(),
                    vector ? mapper.writeValueAsString(embedding(chunk.content())) : digest(chunk.content())});
            String column = vector ? "vector_json" : "keyword_digest";
            jdbc.batchUpdate("INSERT INTO m19e_chunk_index(chunk_id," + column + ") VALUES (?,?) "
                    + "ON DUPLICATE KEY UPDATE " + column + "=VALUES(" + column + ")", values);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark model interrupted", e);
        }
    }
}
