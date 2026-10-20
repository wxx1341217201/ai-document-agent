package com.wxx.aidocumentagent.conversation.application;

/** 应用层消息输入；隐藏推理或工具中间过程不属于该命令。 */
public record SendConversationMessageCommand(String content) {

    public SendConversationMessageCommand {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content不能为空");
        }
    }
}
