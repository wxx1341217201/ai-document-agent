package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.ErrorCode;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.storage.DocumentStorageException;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/** 只有已知的暂态存储/数据库/显式标记故障才会进入有限重试。 */
@Component
public class IngestionFailureClassifier {

    public IngestionFailureSummary classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof VectorIndexException vectorIndexException) {
                return new IngestionFailureSummary(vectorIndexException.getErrorCode().code(),
                        vectorIndexException.getErrorCode().defaultMessage(), vectorIndexException.isRetryable());
            }
            if (current instanceof KeywordIndexException keywordIndexException) {
                return new IngestionFailureSummary(keywordIndexException.getErrorCode().code(),
                        keywordIndexException.getErrorCode().defaultMessage(), keywordIndexException.isRetryable());
            }
            if (current instanceof BusinessException businessException) {
                ErrorCode code = businessException.getErrorCode();
                boolean retryable = code == DocumentErrorCode.PARSE_SOURCE_FAILURE
                        || code == DocumentErrorCode.STORAGE_FAILURE;
                return new IngestionFailureSummary(code.code(), code.defaultMessage(), retryable);
            }
            if (current instanceof RetryableIngestionException || current instanceof DocumentStorageException
                    || current instanceof TransientDataAccessException || current instanceof QueryTimeoutException
                    || current instanceof IngestionPublishException) {
                return new IngestionFailureSummary(IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                        IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage(), true);
            }
        }
        return new IngestionFailureSummary(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage(), false);
    }
}
