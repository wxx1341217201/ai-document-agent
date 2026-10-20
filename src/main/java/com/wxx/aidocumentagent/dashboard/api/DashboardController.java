package com.wxx.aidocumentagent.dashboard.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.dashboard.api.dto.DashboardPageResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentDocumentResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentKnowledgeBaseResponse;
import com.wxx.aidocumentagent.dashboard.application.DashboardApplicationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardApplicationService dashboard;

    public DashboardController(DashboardApplicationService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<DashboardPageResponse<RecentKnowledgeBaseResponse>> knowledgeBases(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size) {
        return ApiResponse.success(dashboard.knowledgeBases(page, size), TraceIdFilter.currentTraceId());
    }

    @GetMapping("/documents")
    public ApiResponse<DashboardPageResponse<RecentDocumentResponse>> documents(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size) {
        return ApiResponse.success(dashboard.documents(page, size), TraceIdFilter.currentTraceId());
    }
}
