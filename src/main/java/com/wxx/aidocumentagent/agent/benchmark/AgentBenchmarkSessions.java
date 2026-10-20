package com.wxx.aidocumentagent.agent.benchmark;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 只创建/清理本 Runner 新建的空会话；历史是每个任务的真实回答快照。 */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.agent", name = "enabled", havingValue = "true")
class AgentBenchmarkSessions {
    private final KnowledgeBaseRepository knowledgeBases;
    private final ConversationRepository conversations;
    private final Map<Long, Scope> owned = new ConcurrentHashMap<>();
    AgentBenchmarkSessions(KnowledgeBaseRepository knowledgeBases, ConversationRepository conversations) {
        this.knowledgeBases = knowledgeBases; this.conversations = conversations;
    }
    @Transactional
    public long open(long kb, AgentMode mode, String runId, String caseId) {
        if (kb <= 0 || !knowledgeBases.existsById(kb)) throw new IllegalArgumentException("BENCHMARK_KNOWLEDGE_BASE_NOT_FOUND");
        String title = "M19C:" + runId + ":" + caseId;
        Conversation saved = conversations.saveAndFlush(Conversation.create(kb, title, ConversationAgentMode.valueOf(mode.name())));
        if (saved.getId() == null || owned.putIfAbsent(saved.getId(), new Scope(kb, title)) != null) throw new IllegalStateException("BENCHMARK_SESSION_ID_INVALID");
        return saved.getId();
    }
    @Transactional
    public void close(long kb, long id) {
        Scope owner = owned.get(id);
        if (owner == null || owner.knowledgeBaseId() != kb) throw new IllegalArgumentException("BENCHMARK_SESSION_NOT_OWNED");
        Conversation saved = conversations.findByIdAndKnowledgeBaseId(id, kb).orElseThrow(() -> new IllegalArgumentException("BENCHMARK_SESSION_NOT_FOUND"));
        if (!owner.title().equals(saved.getTitle()) || saved.getKnowledgeBaseId() != kb) throw new IllegalArgumentException("BENCHMARK_SESSION_SCOPE_CHANGED");
        conversations.delete(saved);
        conversations.flush();
        owned.remove(id);
    }
    private record Scope(long knowledgeBaseId, String title) { }
}
