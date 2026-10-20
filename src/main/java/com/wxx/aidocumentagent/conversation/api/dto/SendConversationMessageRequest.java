package com.wxx.aidocumentagent.conversation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 非流式发送消息请求；本模块只保存最终助手回答，不保存模型中间输出。 */
public record SendConversationMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2_000, message = "消息内容不能超过2000个字符")
        String content) {

    public SendConversationMessageRequest {
        content = content == null ? null : content.strip();
    }
}
