package com.wxx.aidocumentagent.conversation.api.dto;

import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import jakarta.validation.constraints.Size;

/** 创建会话请求；未提供标题时由应用层使用安全的默认标题。 */
public record CreateConversationRequest(
        @Size(max = 128, message = "会话标题不能超过128个字符")
        String title,
        ConversationAgentMode agentMode) {

    public CreateConversationRequest {
        title = title == null ? null : title.strip();
        agentMode = agentMode == null ? ConversationAgentMode.RETRIEVAL_FIRST : agentMode;
    }
}
