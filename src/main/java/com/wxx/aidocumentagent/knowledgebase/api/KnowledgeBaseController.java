package com.wxx.aidocumentagent.knowledgebase.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.knowledgebase.api.dto.CreateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBasePageResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBaseResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.UpdateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.application.KnowledgeBaseApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理的 HTTP 协议适配器。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;

    public KnowledgeBaseController(KnowledgeBaseApplicationService knowledgeBaseApplicationService) {
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBaseResponse response = knowledgeBaseApplicationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(success(response));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> getById(@PathVariable @Positive long id) {
        return success(knowledgeBaseApplicationService.getById(id));
    }

    @GetMapping
    public ApiResponse<KnowledgeBasePageResponse> list(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "页码不能小于0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页数量不能小于1")
            @Max(value = MAX_PAGE_SIZE, message = "每页数量不能超过100") int size) {
        return success(knowledgeBaseApplicationService.list(page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable @Positive long id,
                                                       @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return success(knowledgeBaseApplicationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive long id) {
        knowledgeBaseApplicationService.delete(id);
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return ApiResponse.success(data, TraceIdFilter.currentTraceId());
    }
}
