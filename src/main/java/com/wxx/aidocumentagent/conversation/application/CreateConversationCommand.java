package com.wxx.aidocumentagent.conversation.application;

import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;

/** 应用层输入，避免将 HTTP DTO 直接传递到领域操作。 */
public record CreateConversationCommand(String title, ConversationAgentMode agentMode) {
}
