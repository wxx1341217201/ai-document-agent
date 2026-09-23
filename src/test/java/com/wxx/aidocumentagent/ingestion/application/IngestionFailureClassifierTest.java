package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionFailureClassifierTest {

    @Test
    void 向量限流会作为可重试故障进入既有有限重试策略() {
        IngestionFailureSummary summary = new IngestionFailureClassifier()
                .classify(new VectorIndexException(VectorIndexErrorCode.EMBEDDING_RATE_LIMITED));

        assertThat(summary.code()).isEqualTo("EMBEDDING_RATE_LIMITED");
        assertThat(summary.retryable()).isTrue();
    }

    @Test
    void 向量鉴权失败不会进入重试() {
        IngestionFailureSummary summary = new IngestionFailureClassifier()
                .classify(new VectorIndexException(VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED));

        assertThat(summary.code()).isEqualTo("EMBEDDING_AUTHENTICATION_FAILED");
        assertThat(summary.retryable()).isFalse();
    }

    @Test
    void Elasticsearch暂时不可用会复用既有有限重试策略() {
        IngestionFailureSummary summary = new IngestionFailureClassifier()
                .classify(new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_UNAVAILABLE));

        assertThat(summary.code()).isEqualTo("ELASTICSEARCH_UNAVAILABLE");
        assertThat(summary.retryable()).isTrue();
    }
}
