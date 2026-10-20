package com.wxx.aidocumentagent.agent.execution.persistence;

import java.time.LocalDateTime;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** Read-only tool audit. Side-effecting tools are rejected by ToolRegistry in durable executions. */
@Entity
@Table(name = "tool_invocation")
public class ToolInvocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "execution_id", nullable = false, length = 36, updatable = false) private String executionId;
    @Column(name = "knowledge_base_id", nullable = false, updatable = false) private long knowledgeBaseId;
    @Column(name = "invocation_key", nullable = false, length = 128, updatable = false) private String invocationKey;
    @Column(name = "tool_name", nullable = false, length = 64, updatable = false) private String toolName;
    @Column(nullable = false, length = 16) private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments_summary_json", nullable = false, columnDefinition = "json", updatable = false)
    private JsonNode argumentsSummaryJson;
    @Column(name = "elapsed_millis") private Long elapsedMillis;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "side_effecting", nullable = false, updatable = false) private boolean sideEffecting;
    @Column(name = "idempotency_key", length = 128, updatable = false) private String idempotencyKey;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected ToolInvocation() { }

    public static ToolInvocation readOnly(String executionId, long knowledgeBaseId, String invocationKey,
                                          AgentToolCallSummary summary, JsonNode argumentsSummary) {
        ToolInvocation item = new ToolInvocation();
        item.executionId = executionId;
        item.knowledgeBaseId = knowledgeBaseId;
        item.invocationKey = invocationKey;
        item.toolName = summary.toolName();
        item.status = summary.status().name();
        item.argumentsSummaryJson = argumentsSummary;
        item.elapsedMillis = summary.elapsedMillis();
        item.errorCode = summary.errorCode() == null ? null : summary.errorCode().name();
        return item;
    }

    @PrePersist void created() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    public String getInvocationKey() { return invocationKey; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public JsonNode getArgumentsSummaryJson() { return argumentsSummaryJson; }
    public Long getElapsedMillis() { return elapsedMillis; }
    public String getErrorCode() { return errorCode; }
}
