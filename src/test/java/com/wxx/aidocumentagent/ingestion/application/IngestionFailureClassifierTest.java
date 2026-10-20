package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionFailureClassifierTest {

    @Test
    void permanentOcrFailuresKeepTheirActionableCodesWithoutRepeatingLongRecognition() {
        for (var code : java.util.List.of(DocumentErrorCode.OCR_UNAVAILABLE, DocumentErrorCode.OCR_FAILED,
                DocumentErrorCode.OCR_TIMEOUT, DocumentErrorCode.OCR_LIMIT_EXCEEDED)) {
            var summary = new IngestionFailureClassifier().classify(new BusinessException(code));
            assertThat(summary.code()).isEqualTo(code.code());
            assertThat(summary.message()).isEqualTo(code.defaultMessage());
            assertThat(summary.retryable()).isFalse();
        }
    }

    @Test
    void brokenPdfTextLayerFailsWithActionableMessageWithoutRetrying() {
        var summary = new IngestionFailureClassifier()
                .classify(new BusinessException(DocumentErrorCode.PARSE_UNREADABLE_TEXT));

        assertThat(summary.code()).isEqualTo("DOCUMENT_PARSE_UNREADABLE_TEXT");
        assertThat(summary.message()).contains("OCR", "TXT/DOCX");
        assertThat(summary.retryable()).isFalse();
    }

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
