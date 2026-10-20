package com.wxx.aidocumentagent.conversation.infrastructure.persistence;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 所有消息读取均通过 conversation.knowledgeBaseId 显式约束，防止跨知识库串读。 */
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    @Query("select message from ConversationMessage message join Conversation conversation "
            + "on conversation.id = message.conversationId where message.conversationId = :conversationId "
            + "and conversation.knowledgeBaseId = :knowledgeBaseId "
            + "order by message.sequenceNo asc, message.id asc")
    Page<ConversationMessage> findPageByConversationIdAndKnowledgeBaseId(
            @Param("conversationId") long conversationId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            Pageable pageable);

    @Query("select message from ConversationMessage message join Conversation conversation "
            + "on conversation.id = message.conversationId where message.conversationId = :conversationId "
            + "and conversation.knowledgeBaseId = :knowledgeBaseId "
            + "order by message.sequenceNo desc, message.id desc")
    List<ConversationMessage> findRecentByConversationIdAndKnowledgeBaseId(
            @Param("conversationId") long conversationId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            Pageable pageable);
}
