package com.wxx.aidocumentagent.agent.benchmark;

import java.util.Optional;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentBenchmarkSessionsTest {
    @Test void cleanupCanOnlyDeleteNewlyOwnedConversationWithMatchingKnowledgeBase() {
        var kb = mock(KnowledgeBaseRepository.class); var repo = mock(ConversationRepository.class);
        when(kb.existsById(2L)).thenReturn(true);
        when(repo.saveAndFlush(any())).thenAnswer(call -> {
            Conversation created = call.getArgument(0); ReflectionTestUtils.setField(created, "id", 999L); return created;
        });
        var sessions = new AgentBenchmarkSessions(kb, repo);
        assertThatThrownBy(() -> sessions.close(2, 10)).hasMessage("BENCHMARK_SESSION_NOT_OWNED");
        long id = sessions.open(2, AgentMode.REACT, "unit-run", "UNIT-001");
        assertThatThrownBy(() -> sessions.close(99, id)).hasMessage("BENCHMARK_SESSION_NOT_OWNED");
        var created = org.mockito.ArgumentCaptor.forClass(Conversation.class); verify(repo).saveAndFlush(created.capture());
        when(repo.findByIdAndKnowledgeBaseId(999, 2)).thenReturn(Optional.of(created.getValue()));
        sessions.close(2, id);
        verify(repo).findByIdAndKnowledgeBaseId(999, 2); verify(repo).delete(created.getValue());
        verify(repo, never()).findById(any());
    }
}
