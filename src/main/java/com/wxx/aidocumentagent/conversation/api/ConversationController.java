package com.wxx.aidocumentagent.conversation.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessagePageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationPageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationResponse;
import com.wxx.aidocumentagent.conversation.api.dto.CreateConversationRequest;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageRequest;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.application.ConversationApplicationService;
import com.wxx.aidocumentagent.conversation.application.CreateConversationCommand;
import com.wxx.aidocumentagent.conversation.application.SendConversationMessageCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller 仅做 HTTP 协议适配、参数校验与应用服务调用。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1")
public class ConversationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationApplicationService conversationApplicationService;

    public ConversationController(ConversationApplicationService conversationApplicationService) {
        this.conversationApplicationService = conversationApplicationService;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/conversations")
    public ResponseEntity<ApiResponse<ConversationResponse>> create(@PathVariable @Positive long knowledgeBaseId,
                                                                      @Valid @RequestBody CreateConversationRequest request) {
        ConversationResponse response = conversationApplicationService.create(knowledgeBaseId,
                new CreateConversationCommand(request.title(), request.agentMode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(success(response));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/conversations")
    public ApiResponse<ConversationPageResponse> list(@PathVariable @Positive long knowledgeBaseId,
                                                        @RequestParam(defaultValue = "0") @Min(value = 0,
                                                                message = "页码不能小于0") int page,
                                                        @RequestParam(defaultValue = "20") @Min(value = 1,
                                                                message = "每页数量不能小于1")
                                                        @Max(value = MAX_PAGE_SIZE, message = "每页数量不能超过100") int size) {
        return success(conversationApplicationService.list(knowledgeBaseId, page, size));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<ConversationMessagePageResponse> listMessages(@PathVariable @Positive long conversationId,
                                                                       @RequestParam(defaultValue = "0") @Min(value = 0,
                                                                               message = "页码不能小于0") int page,
                                                                       @RequestParam(defaultValue = "20") @Min(value = 1,
                                                                               message = "每页数量不能小于1")
                                                                       @Max(value = MAX_PAGE_SIZE,
                                                                               message = "每页数量不能超过100") int size) {
        return success(conversationApplicationService.listMessages(conversationId, page, size));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<SendConversationMessageResponse> sendMessage(@PathVariable @Positive long conversationId,
                                                                      @Valid @RequestBody SendConversationMessageRequest request) {
        return success(conversationApplicationService.sendMessage(conversationId,
                new SendConversationMessageCommand(request.content())));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> delete(@PathVariable @Positive long conversationId) {
        conversationApplicationService.delete(conversationId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return ApiResponse.success(data, TraceIdFilter.currentTraceId());
    }
}
