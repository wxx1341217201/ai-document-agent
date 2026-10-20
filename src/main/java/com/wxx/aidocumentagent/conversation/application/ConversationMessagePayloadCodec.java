package com.wxx.aidocumentagent.conversation.application;

import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationTokenUsageResponse;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 仅编解码引用和 token 的结构化 JSON；绝不接收或保存模型提示词、隐藏推理与中间输出。 */
public final class ConversationMessagePayloadCodec {

    private static final TypeReference<List<RagCitationResponse>> CITATIONS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ConversationMessagePayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    public JsonNode encodeCitations(List<RagCitationResponse> citations) {
        return encode(citations == null ? List.of() : List.copyOf(citations));
    }

    public JsonNode encodeTokenUsage(RagChatCompletion completion) {
        if (completion == null) {
            return null;
        }
        return encode(new ConversationTokenUsageResponse(completion.promptTokens(), completion.completionTokens(),
                completion.totalTokens()));
    }

    public JsonNode encodeTokenUsage(AgentUsage usage) {
        if (usage == null || usage.modelCalls() == null || usage.modelCalls() == 0) {
            return null;
        }
        return encode(new ConversationTokenUsageResponse(usage.promptTokens(), usage.completionTokens(),
                usage.totalTokens()));
    }

    public JsonNode encodeAgentSteps(List<AgentStepSummary> steps) {
        return encode(steps == null ? List.of() : List.copyOf(steps));
    }

    public JsonNode encodeToolCalls(List<AgentToolCallSummary> calls) {
        return encode(calls == null ? List.of() : List.copyOf(calls));
    }

    public ConversationMessageResponse toResponse(ConversationMessage message) {
        return new ConversationMessageResponse(message.getId(), message.getConversationId(), message.getSequenceNo(),
                message.getRole(), message.getContent(), decodeCitations(message.getCitationsJson()), message.getModelName(),
                decodeTokenUsage(message.getTokenUsageJson()), message.getCreatedAt());
    }

    private List<RagCitationResponse> decodeCitations(JsonNode json) {
        if (json == null || json.isNull()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(objectMapper.writeValueAsString(json), CITATIONS_TYPE));
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("会话引用元数据无法解析", exception);
        }
    }

    private ConversationTokenUsageResponse decodeTokenUsage(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(json), ConversationTokenUsageResponse.class);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("会话token元数据无法解析", exception);
        }
    }

    private JsonNode encode(Object value) {
        try {
            return objectMapper.valueToTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("会话消息元数据序列化失败", exception);
        }
    }
}
