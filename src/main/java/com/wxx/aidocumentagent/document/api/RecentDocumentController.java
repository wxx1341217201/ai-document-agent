package com.wxx.aidocumentagent.document.api;

import java.util.List;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.application.DocumentApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前工作空间最近更新的五份文档，避免客户端遍历所有知识库和文档分页。 */
@RestController
public class RecentDocumentController {

    private final DocumentApplicationService documentApplicationService;

    public RecentDocumentController(DocumentApplicationService documentApplicationService) {
        this.documentApplicationService = documentApplicationService;
    }

    @GetMapping("/api/v1/documents/recent")
    public ApiResponse<List<DocumentResponse>> recent() {
        return ApiResponse.success(documentApplicationService.recent(), TraceIdFilter.currentTraceId());
    }
}
