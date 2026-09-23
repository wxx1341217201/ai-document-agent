package com.wxx.aidocumentagent.rag.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.rag.api.dto.KnowledgeBaseQueryRequest;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 仅做 HTTP 协议适配、参数校验和应用服务调用；问答业务规则位于 RagQueryApplicationService。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeBaseQueryController {

    private final RagQueryApplicationService ragQueryApplicationService;

    public KnowledgeBaseQueryController(RagQueryApplicationService ragQueryApplicationService) {
        this.ragQueryApplicationService = ragQueryApplicationService;
    }

    @PostMapping("/query")
    public ApiResponse<RagQueryResponse> query(@PathVariable @Positive long knowledgeBaseId,
                                                @Valid @RequestBody KnowledgeBaseQueryRequest request) {
        RagQueryResponse response = ragQueryApplicationService.query(knowledgeBaseId,
                new RagQueryCommand(request.question(), request.topK(), request.rerankEnabled()));
        return ApiResponse.success(response, TraceIdFilter.currentTraceId());
    }
}
