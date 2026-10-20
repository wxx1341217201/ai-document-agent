package com.wxx.aidocumentagent.conversation.infrastructure.persistence;

import java.util.Optional;

import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Page<Conversation> findByKnowledgeBaseId(long knowledgeBaseId, Pageable pageable);

    Optional<Conversation> findByIdAndKnowledgeBaseId(long id, long knowledgeBaseId);

    boolean existsByIdAndKnowledgeBaseIdAndAgentMode(long id, long knowledgeBaseId, ConversationAgentMode agentMode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select conversation from Conversation conversation where conversation.id = :conversationId "
            + "and conversation.knowledgeBaseId = :knowledgeBaseId")
    Optional<Conversation> findLockedByIdAndKnowledgeBaseId(@Param("conversationId") long conversationId,
                                                              @Param("knowledgeBaseId") long knowledgeBaseId);
}
