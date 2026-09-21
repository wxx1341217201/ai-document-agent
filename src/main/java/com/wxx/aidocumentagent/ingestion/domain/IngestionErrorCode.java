package com.wxx.aidocumentagent.ingestion.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** 摄取流水线只记录这些稳定且安全的错误码，绝不保存底层异常详情。 */
public enum IngestionErrorCode implements ErrorCode {

    INGESTION_JOB_NOT_FOUND("INGESTION_JOB_NOT_FOUND", HttpStatus.NOT_FOUND, "摄取任务不存在"),
    INGESTION_BATCH_NOT_FOUND("INGESTION_BATCH_NOT_FOUND", HttpStatus.NOT_FOUND, "摄取批任务不存在"),
    INGESTION_RETRY_NOT_ALLOWED("INGESTION_RETRY_NOT_ALLOWED", HttpStatus.CONFLICT, "当前摄取任务不能手动重试"),
    INGESTION_INVALID_MESSAGE("INGESTION_INVALID_MESSAGE", HttpStatus.BAD_REQUEST, "摄取消息不合法"),
    INGESTION_SCOPE_MISMATCH("INGESTION_SCOPE_MISMATCH", HttpStatus.BAD_REQUEST, "摄取消息与知识库或文档不匹配"),
    INGESTION_ILLEGAL_STATE("INGESTION_ILLEGAL_STATE", HttpStatus.CONFLICT, "摄取任务状态转换不合法"),
    INGESTION_BATCH_CHUNK_MISMATCH("INGESTION_BATCH_CHUNK_MISMATCH", HttpStatus.CONFLICT, "批任务对应的chunk范围不完整"),
    INGESTION_PUBLISH_FAILURE("INGESTION_PUBLISH_FAILURE", HttpStatus.SERVICE_UNAVAILABLE, "摄取消息发布失败"),
    INGESTION_TRANSIENT_FAILURE("INGESTION_TRANSIENT_FAILURE", HttpStatus.SERVICE_UNAVAILABLE, "摄取任务暂时失败"),
    INGESTION_PROCESSING_FAILURE("INGESTION_PROCESSING_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, "摄取任务处理失败");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    IngestionErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
