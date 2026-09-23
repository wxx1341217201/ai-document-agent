package com.wxx.aidocumentagent.vector;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/** 将模型/向量库 SDK 故障归一为可安全入库且可决定是否重试的错误。 */
@Component
public class VectorIndexExceptionTranslator {

    public VectorIndexException translateEmbedding(Throwable failure) {
        return translate(failure, Operation.EMBEDDING);
    }

    public VectorIndexException translateVectorStore(Throwable failure) {
        return translate(failure, Operation.VECTOR_STORE);
    }

    private VectorIndexException translate(Throwable failure, Operation operation) {
        if (failure instanceof VectorIndexException vectorIndexException) {
            return vectorIndexException;
        }
        return new VectorIndexException(resolveCode(failure, operation), failure);
    }

    private VectorIndexErrorCode resolveCode(Throwable failure, Operation operation) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof VectorIndexException vectorIndexException) {
                return vectorIndexException.getErrorCode();
            }
            if (isDimensionMismatch(current)) {
                return VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH;
            }
            if (isTimeout(current)) {
                return operation == Operation.EMBEDDING
                        ? VectorIndexErrorCode.EMBEDDING_TIMEOUT : VectorIndexErrorCode.QDRANT_TIMEOUT;
            }
            VectorIndexErrorCode httpCode = httpCode(current, operation);
            if (httpCode != null) {
                return httpCode;
            }
            VectorIndexErrorCode grpcCode = grpcCode(current, operation);
            if (grpcCode != null) {
                return grpcCode;
            }
            String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (className.contains("ratelimit") || className.contains("rate_limit")) {
                return operation == Operation.EMBEDDING
                        ? VectorIndexErrorCode.EMBEDDING_RATE_LIMITED : VectorIndexErrorCode.QDRANT_UNAVAILABLE;
            }
            if (className.contains("authentication") || className.contains("unauthorized")) {
                return operation == Operation.EMBEDDING
                        ? VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED
                        : VectorIndexErrorCode.QDRANT_AUTHENTICATION_FAILED;
            }
        }
        return operation == Operation.EMBEDDING
                ? VectorIndexErrorCode.EMBEDDING_REQUEST_FAILED : VectorIndexErrorCode.QDRANT_OPERATION_FAILED;
    }

    private boolean isTimeout(Throwable failure) {
        return failure instanceof TimeoutException || failure instanceof SocketTimeoutException
                || failure instanceof HttpTimeoutException;
    }

    private boolean isDimensionMismatch(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("dimension") || normalized.contains("vector size")
                || normalized.contains("vector length");
    }

    private VectorIndexErrorCode httpCode(Throwable failure, Operation operation) {
        if (!(failure instanceof RestClientResponseException responseException)) {
            return null;
        }
        int status = responseException.getStatusCode().value();
        if (status == 401 || status == 403) {
            return operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED
                    : VectorIndexErrorCode.QDRANT_AUTHENTICATION_FAILED;
        }
        if (status == 429) {
            return operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_RATE_LIMITED : VectorIndexErrorCode.QDRANT_UNAVAILABLE;
        }
        if (status == 408 || status == 504) {
            return operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_TIMEOUT : VectorIndexErrorCode.QDRANT_TIMEOUT;
        }
        return null;
    }

    private VectorIndexErrorCode grpcCode(Throwable failure, Operation operation) {
        Status status = null;
        if (failure instanceof StatusRuntimeException exception) {
            status = exception.getStatus();
        }
        else if (failure instanceof StatusException exception) {
            status = exception.getStatus();
        }
        if (status == null) {
            return null;
        }
        return switch (status.getCode()) {
            case DEADLINE_EXCEEDED -> operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_TIMEOUT : VectorIndexErrorCode.QDRANT_TIMEOUT;
            case UNAUTHENTICATED, PERMISSION_DENIED -> operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED
                    : VectorIndexErrorCode.QDRANT_AUTHENTICATION_FAILED;
            case RESOURCE_EXHAUSTED -> operation == Operation.EMBEDDING
                    ? VectorIndexErrorCode.EMBEDDING_RATE_LIMITED : VectorIndexErrorCode.QDRANT_UNAVAILABLE;
            case UNAVAILABLE -> VectorIndexErrorCode.QDRANT_UNAVAILABLE;
            default -> null;
        };
    }

    private enum Operation {
        EMBEDDING,
        VECTOR_STORE
    }
}
