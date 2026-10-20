package com.wxx.aidocumentagent.conversation.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Objects;

import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** conversation 表的持久化模型；消息序号由同一会话锁定后分配。 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private long knowledgeBaseId;

    @Column(nullable = false, length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_mode", nullable = false, length = 32, updatable = false)
    private ConversationAgentMode agentMode;

    @Column(name = "last_message_sequence", nullable = false)
    private long lastMessageSequence;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Conversation() {
    }

    private Conversation(long knowledgeBaseId, String title, ConversationAgentMode agentMode) {
        if (knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        this.knowledgeBaseId = knowledgeBaseId;
        this.title = requireTitle(title);
        this.agentMode = Objects.requireNonNull(agentMode, "agentMode不能为空");
    }

    public static Conversation create(long knowledgeBaseId, String title, ConversationAgentMode agentMode) {
        return new Conversation(knowledgeBaseId, title, agentMode);
    }

    /**
     * 数据库的 PESSIMISTIC_WRITE 锁是跨实例并发控制的主保障；同步块也避免同一持久化上下文中的重复分配。
     */
    public synchronized long reserveMessageSequences(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("消息序号数量必须大于0");
        }
        if (lastMessageSequence > Long.MAX_VALUE - count) {
            throw new IllegalStateException("消息序号已耗尽");
        }
        long first = lastMessageSequence + 1L;
        lastMessageSequence += count;
        return first;
    }

    @PrePersist
    void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getTitle() {
        return title;
    }

    public ConversationAgentMode getAgentMode() {
        return agentMode;
    }

    public long getLastMessageSequence() {
        return lastMessageSequence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("title不能为空");
        }
        String result = value.strip();
        if (result.length() > 128) {
            throw new IllegalArgumentException("title不能超过128个字符");
        }
        return result;
    }
}
