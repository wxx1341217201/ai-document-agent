package com.wxx.aidocumentagent.ingestion.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.ingestion.api.dto.IngestionJobResponse;
import com.wxx.aidocumentagent.ingestion.application.DocumentIngestionRetryApplicationService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅处理 HTTP 协议与参数校验；重试状态机位于应用服务。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentIngestionController {

    private final DocumentIngestionRetryApplicationService retryApplicationService;

    public DocumentIngestionController(DocumentIngestionRetryApplicationService retryApplicationService) {
        this.retryApplicationService = retryApplicationService;
    }

    @PostMapping("/{documentId}/ingestion/retry")
    public ResponseEntity<ApiResponse<IngestionJobResponse>> retry(@PathVariable @Positive long knowledgeBaseId,
                                                                     @PathVariable @Positive long documentId) {
        IngestionJobResponse response = retryApplicationService.retry(knowledgeBaseId, documentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response, TraceIdFilter.currentTraceId()));
    }
}
