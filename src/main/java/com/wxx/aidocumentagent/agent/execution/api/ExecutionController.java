package com.wxx.aidocumentagent.agent.execution.api;

import com.wxx.aidocumentagent.agent.execution.api.dto.CreateExecutionRequest;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionResponse;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionPageResponse;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionApplicationService;
import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/executions")
@ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionController {
    private final ExecutionApplicationService service;

    public ExecutionController(ExecutionApplicationService service) { this.service = service; }

    @GetMapping
    public ApiResponse<ExecutionPageResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) AgentMode agentMode) {
        return success(service.list(page, size, status, agentMode));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExecutionResponse>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateExecutionRequest request) {
        var created = service.create(idempotencyKey, request);
        return ResponseEntity.status(created.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(success(created.response()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExecutionResponse> get(@PathVariable String id,
                                               @RequestParam @Positive long knowledgeBaseId) {
        return success(service.get(id, knowledgeBaseId));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<ExecutionResponse>> resume(@PathVariable String id,
                                                                  @RequestParam @Positive long knowledgeBaseId) {
        return ResponseEntity.accepted().body(success(service.resume(id, knowledgeBaseId)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ExecutionResponse> cancel(@PathVariable String id,
                                                  @RequestParam @Positive long knowledgeBaseId) {
        return success(service.cancel(id, knowledgeBaseId));
    }

    private <T> ApiResponse<T> success(T response) {
        return ApiResponse.success(response, TraceIdFilter.currentTraceId());
    }
}
