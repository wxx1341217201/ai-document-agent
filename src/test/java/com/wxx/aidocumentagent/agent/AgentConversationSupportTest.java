package com.wxx.aidocumentagent.agent;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentConversationSupportTest {
    @Test
    void historyPreservesUserTopicAndRemovesStaleCitationIdentifiers() {
        var history = AgentConversationSupport.history(List.of(
                new AgentMemoryMessage(1, AgentMemoryMessage.Role.USER, "Jane is riding to Netherfield"),
                new AgentMemoryMessage(2, AgentMemoryMessage.Role.ASSISTANT, "<think>hidden</think>Prior answer [C1]")));
        assertThat(AgentConversationSupport.prompt(history)).contains("Jane", "NOT evidence", "Prior answer")
                .doesNotContain("[C1]", "hidden", "<think>");
    }

    @Test
    void historyStaysWithinItsCharacterAndMessageBudgetWithoutBreakingSurrogates() {
        List<AgentMemoryMessage> memory = new ArrayList<>();
        for (int i = 1; i <= 20; i++) memory.add(new AgentMemoryMessage(i, AgentMemoryMessage.Role.USER,
                "x".repeat(999) + "😀".repeat(2_000)));
        var history = AgentConversationSupport.history(memory);
        assertThat(history).hasSizeLessThanOrEqualTo(10);
        assertThat(history.stream().mapToInt(turn -> turn.content().length()).sum()).isLessThanOrEqualTo(6_000);
        assertThat(history).allSatisfy(turn -> assertThat(Character.isHighSurrogate(
                turn.content().charAt(turn.content().length() - 1))).isFalse());
    }
}
