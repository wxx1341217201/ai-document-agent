package com.wxx.aidocumentagent.conversation.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Objects;

import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * 会话可审计消息。content 只接受用户输入、最终回答或结构化摘要；隐藏推理不得传入该实体。
 */
@Entity
@Table(name = "conversation_message")
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ConversationMessageRole role;

    @Column(nullable = false, columnDefinition = "mediumtext", updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations_json", columnDefinition = "json", updatable = false)
    private JsonNode citationsJson;

    @Column(name = "model_name", length = 128, updatable = false)
    private String modelName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage_json", columnDefinition = "json", updatable = false)
    private JsonNode tokenUsageJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_steps_json", columnDefinition = "json", updatable = false)
    private JsonNode agentStepsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_json", columnDefinition = "json", updatable = false)
    private JsonNode toolCallsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ConversationMessage() {
    }

    private ConversationMessage(long conversationId, long sequenceNo, ConversationMessageRole role, String content,
                                JsonNode citationsJson, String modelName, JsonNode tokenUsageJson,
                                JsonNode agentStepsJson, JsonNode toolCallsJson) {
        if (conversationId <= 0L || sequenceNo <= 0L) {
            throw new IllegalArgumentException("conversationId和sequenceNo必须大于0");
        }
        this.conversationId = conversationId;
        this.sequenceNo = sequenceNo;
        this.role = Objects.requireNonNull(role, "role不能为空");
        this.content = requireContent(content);
        this.citationsJson = citationsJson;
        this.modelName = normalizeModelName(modelName);
        this.tokenUsageJson = tokenUsageJson;
        this.agentStepsJson = agentStepsJson;
        this.toolCallsJson = toolCallsJson;
    }

    public static ConversationMessage user(long conversationId, long sequenceNo, String content) {
        return new ConversationMessage(conversationId, sequenceNo, ConversationMessageRole.USER, content, null, null,
                null, null, null);
    }

    public static ConversationMessage assistant(long conversationId, long sequenceNo, String content,
                                                JsonNode citationsJson, String modelName, JsonNode tokenUsageJson) {
        return new ConversationMessage(conversationId, sequenceNo, ConversationMessageRole.ASSISTANT, content,
                citationsJson, modelName, tokenUsageJson, null, null);
    }

    public static ConversationMessage assistant(long conversationId, long sequenceNo, String content,
                                                JsonNode citationsJson, String modelName, JsonNode tokenUsageJson,
                                                JsonNode agentStepsJson, JsonNode toolCallsJson) {
        return new ConversationMessage(conversationId, sequenceNo, ConversationMessageRole.ASSISTANT, content,
                citationsJson, modelName, tokenUsageJson, agentStepsJson, toolCallsJson);
    }

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public long getConversationId() {
        return conversationId;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public ConversationMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public JsonNode getCitationsJson() {
        return citationsJson;
    }

    public String getModelName() {
        return modelName;
    }

    public JsonNode getTokenUsageJson() {
        return tokenUsageJson;
    }

    public JsonNode getAgentStepsJson() { return agentStepsJson; }

    public JsonNode getToolCallsJson() { return toolCallsJson; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content不能为空");
        }
        return value;
    }

    private static String normalizeModelName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.strip();
        return result.length() <= 128 ? result : result.substring(0, 128);
    }
}
