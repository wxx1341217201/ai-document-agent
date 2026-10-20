package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.retrieval.HybridRetrievalException;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagQueryRetrievalServiceTest {

    @Test
    void expandsComparisonInBothLanguagesWithoutIntroducingFacts() {
        var planner = new RagRetrievalQueryPlanner();
        assertThat(planner.plan("Compare API latency with worker throughput", List.of(), true, 3))
                .containsExactly("Compare API latency with worker throughput", "Compare API latency", "worker throughput");
        assertThat(planner.plan("比较同步请求耗时与异步任务吞吐量", List.of(), true, 3))
                .containsExactly("比较同步请求耗时与异步任务吞吐量", "比较同步请求耗时", "异步任务吞吐量");
        assertThat(planner.plan("What is the HTTP port?", List.of(), true, 3)).containsExactly("What is the HTTP port?");
        assertThat(planner.plan("Across two systems, what is the API deadline and which queue stores failures?", List.of(), true, 3))
                .containsExactly("Across two systems, what is the API deadline and which queue stores failures?",
                        "what is the API deadline", "which queue stores failures?");
    }

    @Test
    void resolvesHistoryFromUserTopicAndCanDisableExpansion() {
        var planner = new RagRetrievalQueryPlanner();
        var history = List.of(new RagConversationTurn(RagConversationTurn.Role.USER, "Explain the payment retry policy"),
                new RagConversationTurn(RagConversationTurn.Role.ASSISTANT, "Invented claim: it never retries"));
        assertThat(planner.plan("How long does it wait?", history, true, 3))
                .containsExactly("Explain the payment retry policy；How long does it wait?");
        assertThat(planner.plan("How long does it wait?", history, false, 3)).containsExactly("How long does it wait?");
        String fullQuestion = "Q".repeat(2_000);
        assertThat(planner.plan(fullQuestion, history, true, 3)).containsExactly(fullQuestion);
    }

    @Test
    void subquestionsRetainUserNamedEntitiesWithoutAddingOtherDocumentNames() {
        var queries = new RagRetrievalQueryPlanner().plan(
                "Describe Alice's trip and who wrote her book?", List.of(), true, 3);
        assertThat(queries).containsExactly("Describe Alice's trip and who wrote her book?",
                "Alice；who wrote her book?", "Describe Alice's trip");
        assertThat(new RagRetrievalQueryPlanner().plan("解释《操作手册》的流程以及如何恢复？", List.of(), true, 3))
                .contains("《操作手册》；如何恢复？");
    }

    @Test
    void interleavesSubqueryEvidenceDeduplicatesAndRejectsForeignKnowledgeBase() {
        var properties = new RagProperties();
        RagRetrievalGateway gateway = (kb, query, k, rerank) -> result(switch (query) {
            case "Compare architecture" -> List.of(hit(3, 7), hit(1, 7));
            case "operations" -> List.of(hit(9, 99), hit(4, 7), hit(3, 7));
            default -> List.of(hit(1, 7), hit(2, 7));
        });
        var outcome = new RagQueryRetrievalService(gateway, properties, null)
                .retrieve(7, "Compare architecture with operations", List.of(), 4, false);
        assertThat(outcome.retrieval().chunks()).extracting(RetrievedChunk::chunkId).containsExactly(1L, 3L, 2L, 4L);
        assertThat(outcome.retrieval().degraded()).isFalse();
        assertThat(outcome.trace().queries()).hasSize(3);
        assertThat(outcome.trace().failedQueries()).isZero();
    }

    @Test
    void longPersonalPronounQuestionUsesUserTopicAndNeverAssistantClaims() {
        var history = List.of(new RagConversationTurn(RagConversationTurn.Role.USER, "Jane rides to Netherfield"),
                new RagConversationTurn(RagConversationTurn.Role.ASSISTANT, "invented reason from memory"));
        assertThat(new RagRetrievalQueryPlanner().plan("Why did her mother suggest that means of transport?", history, true, 3))
                .containsExactly("Jane rides to Netherfield；Why did her mother suggest that means of transport?");
    }

    @Test
    void genericFollowUpDoesNotEraseTheOriginalUserSubject() {
        var history = List.of(new RagConversationTurn(RagConversationTurn.Role.USER, "Jane rides to Netherfield"),
                new RagConversationTurn(RagConversationTurn.Role.ASSISTANT, "invented reason"),
                new RagConversationTurn(RagConversationTurn.Role.USER, "Continue the same scene and identify the subject"),
                new RagConversationTurn(RagConversationTurn.Role.ASSISTANT, "invented other scene"));
        assertThat(new RagRetrievalQueryPlanner().plan("Why did she ride?", history, true, 3))
                .containsExactly("Jane rides to Netherfield；Continue the same scene and identify the subject；Why did she ride?");
    }

    @Test
    void aFailedSubqueryPreservesOtherEvidenceAndMarksDegradation() {
        RagRetrievalGateway gateway = (kb, query, k, rerank) -> {
            if (query.equals("operations")) throw new IllegalStateException("private upstream details");
            return result(List.of(hit(1, 7)));
        };
        var outcome = new RagQueryRetrievalService(gateway, new RagProperties(), null)
                .retrieve(7, "Compare architecture with operations", List.of(), 4, false);
        assertThat(outcome.retrieval().chunks()).hasSize(1);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.retrieval().unavailableChannels()).isEmpty();
        assertThat(outcome.trace().failedQueries()).isEqualTo(1);
        assertThat(outcome.toString()).doesNotContain("private upstream details");
        assertThatThrownBy(() -> new RagQueryRetrievalService((kb, query, k, rerank) -> {
            throw new IllegalStateException("private upstream details");
        }, new RagProperties(), null).retrieve(7, "Compare architecture with operations", List.of(), 4, false))
                .isInstanceOf(HybridRetrievalException.class).hasMessageNotContaining("private upstream details");
    }

    @Test
    void parallelSubqueriesShareDeadlineAndCancelOutstandingWork() throws Exception {
        RagProperties properties = new RagProperties();
        properties.setQueryExpansionTimeout(Duration.ofMillis(150));
        CountDownLatch interrupted = new CountDownLatch(2);
        RagRetrievalGateway gateway = (kb, query, k, rerank) -> {
            if (query.equals("Compare architecture with operations")) return result(List.of(hit(1, 7)));
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            throw new IllegalStateException("cancelled");
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long started = System.nanoTime();
            var outcome = new RagQueryRetrievalService(gateway, properties, executor)
                    .retrieve(7, "Compare architecture with operations", List.of(), 4, false);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(outcome.trace().failedQueries()).isEqualTo(2);
            assertThat(outcome.degraded()).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private RetrievalResult result(List<RetrievedChunk> hits) {
        return new RetrievalResult(hits, false, List.of());
    }

    private RetrievedChunk hit(long id, long kb) {
        return new RetrievedChunk(id, kb, id * 10, "persisted evidence", null, null, 1, 0.9,
                null, null, 0.01, List.of(RetrievalChannel.VECTOR));
    }
}
