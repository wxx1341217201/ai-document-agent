package com.wxx.aidocumentagent.agent.react;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionContextTest {
    @Test
    void fullRegistryStillReturnsExistingEvidenceAfterAnUnadmittedNewChunk() {
        var context = context(1);
        var first = context.registerEvidence(List.of(chunk(11, "first"))).get(11L);
        var result = context.registerEvidence(List.of(chunk(12, "new"), chunk(11, "first expanded")));
        assertThat(result).containsOnlyKeys(11L);
        assertThat(result.get(11L)).isEqualTo(first);
        assertThat(context.evidence()).singleElement().extracting(EvidenceChunk::text).isEqualTo("first expanded");
        assertThat(context.isAuthorizedChunk(12)).isFalse();
        assertThat(context.remainingEvidenceSlots()).isZero();
    }

    @Test
    void evidenceExpansionPreservesCitationIdentityAndRejectsChangedSourceText() {
        var context = context(2);
        var original = context.registerEvidence(List.of(chunk(11, "verified prefix"))).get(11L);
        assertThat(context.registerEvidence(List.of(chunk(11, "unrelated replacement")))).isEmpty();
        var extended = context.registerEvidence(List.of(chunk(11, "verified prefix with the late fact"))).get(11L);
        assertThat(extended).isEqualTo(original);
        assertThat(context.citations()).hasSize(1);
        assertThat(context.evidence().getFirst().text()).endsWith("the late fact");
    }

    private EvidenceChunk chunk(long id, String text) { return new EvidenceChunk(7, id, 21, "guide", 1, 1, text); }

    @Test
    void changedSourceVersionCannotBeReportedAsAcceptedByReusingAnOldWindowEntry() {
        var request = new AgentRequest("verified", 7, null, AgentMode.REACT, Map.of());
        var execution = AgentExecutionContext.forRequest(request, "version", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        var context = new ToolExecutionContext(7, execution, execution.deadline(), 2, 1_000, 4);
        context.registerEvidence(List.of(new EvidenceChunk(7, 11, 21, "guide", 1, 1, "verified prefix", "a".repeat(64))));
        var rejected = context.registerEvidence(List.of(new EvidenceChunk(7, 11, 21, "guide", 1, 1,
                "verified prefix with a changed version", "b".repeat(64))));
        assertThat(rejected).isEmpty();
        assertThat(context.evidence(11).text()).isEqualTo("verified prefix");
    }

    @Test
    void boundedWindowAdmitsNewGoalEvidenceAndRestoresStableIdsWithoutAuthorizingOldInvisibleText() {
        var request = new AgentRequest("payment retry deadline", 7, null, AgentMode.REACT, Map.of());
        var execution = AgentExecutionContext.forRequest(request, "window", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        var context = new ToolExecutionContext(7, execution, execution.deadline(), 1, 1_000, 4);
        context.setGoal(request.question());
        var first = context.registerEvidence(List.of(chunk(11, "unrelated background"))).get(11L);
        context.registerEvidence(List.of(chunk(12, "payment retry deadline is 20 seconds")));
        assertThat(context.evidence()).singleElement().extracting(EvidenceChunk::chunkId).isEqualTo(12L);
        assertThat(context.isAuthorizedChunk(11)).isTrue();
        assertThat(context.citations()).extracting(citation -> citation.chunkId()).doesNotContain(11L);
        assertThat(context.visible()).singleElement().satisfies(item -> assertThat(item.citation().chunkId()).isEqualTo(12L));
        context.setGoal("unrelated background");
        context.focusOn(List.of("unrelated background"));
        assertThat(context.citations()).containsExactly(first);
        assertThat(context.evidence(11).text()).isEqualTo("unrelated background");
        assertThat(context.remainingEvidenceTokens()).isGreaterThanOrEqualTo(0);
        context.registerEvidence(List.of(chunk(13, "one"), chunk(14, "two"), chunk(15, "three")));
        assertThat(context.registeredCitations()).hasSize(4);
        assertThat(context.isAuthorizedChunk(15)).isFalse();
    }

    @Test
    void overlapAppearsOnlyInTheContinuationAndDoesNotReturnOnRepeatedReads() {
        var context = context(20);
        String overlap = "The service retries five times before stopping. ".repeat(5);
        String prefix = "Earlier independent content. ".repeat(30);
        String earlier = prefix + overlap;
        String later = overlap + "Then a distinct later fact.";
        var citations = context.registerEvidence(List.of(chunk(11, earlier), chunk(12, later)));
        assertThat(context.evidence(11).text()).isEqualTo(prefix);
        assertThat(context.evidence(12).text()).isEqualTo(later);
        assertThat(context.registerEvidence(List.of(chunk(11, earlier))).get(11L)).isEqualTo(citations.get(11L));
        assertThat(context.evidence(11).text()).isEqualTo(prefix);
    }

    @Test
    void totalTokenBudgetBoundsActualEvidenceAndKeepsAnExactSourcePrefix() {
        var request = new AgentRequest("question", 7, null, AgentMode.REACT, Map.of());
        var execution = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        var context = new ToolExecutionContext(7, execution, execution.deadline(), 20, 1_000);
        String source = "verified source text ".repeat(1_000);
        context.registerEvidence(List.of(chunk(11, source), chunk(12, source)));
        assertThat(context.evidence()).isNotEmpty();
        assertThat(context.remainingEvidenceTokens()).isBetween(0, 1_000);
        assertThat(context.evidence().stream().mapToInt(chunk -> EvidenceTokenBudget.tokens(chunk.text())).sum())
                .isLessThanOrEqualTo(1_000);
        assertThat(context.evidence()).allSatisfy(chunk -> assertThat(source).startsWith(chunk.text()));
        assertThat(context.evidence().getFirst().text()).hasSizeLessThan(source.length());
    }

    private ToolExecutionContext context(int capacity) {
        var request = new AgentRequest("question", 7, null, AgentMode.REACT, Map.of());
        var execution = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        return new ToolExecutionContext(7, execution, execution.deadline(), capacity);
    }
}
