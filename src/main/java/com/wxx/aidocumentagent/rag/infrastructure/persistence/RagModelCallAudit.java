package com.wxx.aidocumentagent.rag.infrastructure.persistence;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 模型调用的最小审计记录。此实体刻意没有 question、prompt、answer、异常正文和认证字段，
 * 因而不会持久化隐藏思维链或密钥。
 */
@Entity
@Table(name = "rag_model_call_audit")
public class RagModelCallAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_base_id", nullable = false)
    private long knowledgeBaseId;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "elapsed_millis", nullable = false)
    private long elapsedMillis;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RagModelCallAudit() {
    }

    private RagModelCallAudit(long knowledgeBaseId, String traceId, String modelName, Integer promptTokens,
                              Integer completionTokens, Integer totalTokens, Duration elapsed, String outcome,
                              String errorCode) {
        if (knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        this.knowledgeBaseId = knowledgeBaseId;
        this.traceId = requireText(traceId, "traceId不能为空");
        this.modelName = requireText(modelName, "modelName不能为空");
        validateTokenCount(promptTokens, "promptTokens");
        validateTokenCount(completionTokens, "completionTokens");
        validateTokenCount(totalTokens, "totalTokens");
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.elapsedMillis = Objects.requireNonNull(elapsed, "elapsed不能为空").toMillis();
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsed不能为负数");
        }
        this.outcome = requireText(outcome, "outcome不能为空");
        this.errorCode = errorCode;
    }

    public static RagModelCallAudit success(long knowledgeBaseId, String traceId, RagChatCompletion completion,
                                            Duration elapsed) {
        Objects.requireNonNull(completion, "completion不能为空");
        return new RagModelCallAudit(knowledgeBaseId, traceId, completion.modelName(), completion.promptTokens(),
                completion.completionTokens(), completion.totalTokens(), elapsed, "SUCCESS", null);
    }

    public static RagModelCallAudit failure(long knowledgeBaseId, String traceId, String modelName,
                                            RagChatCompletion completion, Duration elapsed, String errorCode) {
        return new RagModelCallAudit(knowledgeBaseId, traceId, modelName,
                completion == null ? null : completion.promptTokens(),
                completion == null ? null : completion.completionTokens(),
                completion == null ? null : completion.totalTokens(), elapsed, "FAILURE",
                requireText(errorCode, "errorCode不能为空"));
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

    public long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getModelName() {
        return modelName;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void validateTokenCount(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }
}
