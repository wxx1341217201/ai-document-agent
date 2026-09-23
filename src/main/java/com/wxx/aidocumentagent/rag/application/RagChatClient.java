package com.wxx.aidocumentagent.rag.application;

/** 问答模型端口。实现必须使用已配置超时的 Spring AI ChatClient，且不返回隐藏推理内容。 */
public interface RagChatClient {

    RagChatCompletion complete(String systemInstruction, String userPrompt);
}
