package com.wxx.aidocumentagent.vector.infrastructure;

import java.util.List;
import java.util.function.Supplier;

import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import com.wxx.aidocumentagent.vector.VectorProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 将 Spring AI provider 的模型异常归类，并在写入 Qdrant 前校验返回维度。
 * 实际 HTTP 请求超时由 application.yml 同步绑定到 spring.ai.openai.embedding.timeout。
 */
public final class ClassifyingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final VectorProperties properties;
    private final VectorIndexExceptionTranslator exceptionTranslator;

    public ClassifyingEmbeddingModel(EmbeddingModel delegate, VectorProperties properties,
                                     VectorIndexExceptionTranslator exceptionTranslator) {
        this.delegate = delegate;
        this.properties = properties;
        this.exceptionTranslator = exceptionTranslator;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return classify(() -> delegate.call(request));
    }

    @Override
    public float[] embed(String text) {
        return validate(classify(() -> delegate.embed(text)));
    }

    @Override
    public float[] embed(Document document) {
        return validate(classify(() -> delegate.embed(document)));
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        List<float[]> vectors = classify(() -> delegate.embed(documents, options, batchingStrategy));
        if (vectors.size() != documents.size()) {
            throw new VectorIndexException(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "嵌入模型返回的向量数量与chunk数量不一致");
        }
        return vectors.stream().map(this::validate).toList();
    }

    @Override
    public int dimensions() {
        return properties.getEmbedding().getDimensions();
    }

    private float[] validate(float[] vector) {
        if (vector == null || vector.length != dimensions()) {
            throw new VectorIndexException(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH);
        }
        return vector;
    }

    private <T> T classify(Supplier<T> operation) {
        try {
            return operation.get();
        }
        catch (VectorIndexException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw exceptionTranslator.translateEmbedding(exception);
        }
    }
}
