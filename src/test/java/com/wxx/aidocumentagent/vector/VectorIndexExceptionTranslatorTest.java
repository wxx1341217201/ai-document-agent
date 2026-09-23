package com.wxx.aidocumentagent.vector;

import java.util.concurrent.TimeoutException;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VectorIndexExceptionTranslatorTest {

    private final VectorIndexExceptionTranslator translator = new VectorIndexExceptionTranslator();

    @Test
    void 模型超时限流鉴权和维度错误都有稳定分类() {
        assertThat(translator.translateEmbedding(new TimeoutException()).getErrorCode())
                .isEqualTo(VectorIndexErrorCode.EMBEDDING_TIMEOUT);
        assertThat(translator.translateEmbedding(Status.RESOURCE_EXHAUSTED.asRuntimeException()).getErrorCode())
                .isEqualTo(VectorIndexErrorCode.EMBEDDING_RATE_LIMITED);
        assertThat(translator.translateEmbedding(Status.PERMISSION_DENIED.asRuntimeException()).getErrorCode())
                .isEqualTo(VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED);
        assertThat(translator.translateEmbedding(new IllegalArgumentException("vector dimension mismatch")).getErrorCode())
                .isEqualTo(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH);
    }

    @Test
    void 只有可恢复的向量错误标记为可重试() {
        assertThat(translator.translateEmbedding(new TimeoutException()).isRetryable()).isTrue();
        assertThat(translator.translateEmbedding(Status.PERMISSION_DENIED.asRuntimeException()).isRetryable()).isFalse();
    }
}
