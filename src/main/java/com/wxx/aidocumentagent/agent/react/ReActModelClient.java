package com.wxx.aidocumentagent.agent.react;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

/** 每轮只调用模型一次；工具执行由 ReActAgent 控制。 */
@FunctionalInterface
public interface ReActModelClient {
    ChatResponse complete(List<Message> messages, ToolCallback[] tools);
}
