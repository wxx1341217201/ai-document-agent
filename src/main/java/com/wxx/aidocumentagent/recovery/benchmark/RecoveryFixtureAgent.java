package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.List;
import java.util.Map;
import com.wxx.aidocumentagent.agent.*;

/** Deterministic visible answers isolate memory/recovery from model quality and provider outages. */
final class RecoveryFixtureAgent implements DocumentAgent {
    private final AgentMode mode;
    private final RecoveryFixtureService fixture;
    RecoveryFixtureAgent(AgentMode mode, RecoveryFixtureService fixture) { this.mode = mode; this.fixture = fixture; }
    @Override public AgentMode mode() { return mode; }

    @Override public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
        fixture.requireConversation(request.knowledgeBaseId(), request.conversationId());
        var step = new AgentStepSummary("fixture_read", AgentStepSummary.Kind.RETRIEVAL, "read_owned_history",
                AgentStepSummary.Status.SUCCEEDED, null);
        var tool = new AgentToolCallSummary("readOwnedHistory", AgentStepSummary.Status.SUCCEEDED, 0L, null,
                Map.of("topK", Integer.toString(context.memory().messages().size())));
        if (context.durableExecutionId() != null) fixture.recordAttempt(context.durableExecutionId());
        context.checkpointStep(step);
        context.checkpointTool("fixture_read", tool);
        if (request.question().endsWith("|STEP")) fixture.awaitRelease(context.durableExecutionId(), context);
        return new AgentResult(answer(request.question(), context.memory().messages().stream().map(m ->
                        new RecoveryBenchmarkObservation.Memory(m.sequenceNo(),
                                com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole.valueOf(m.role().name()),
                                RecoveryBenchmarkObservation.sha(m.content()))).toList(), fixture.mapper()),
                List.of(), List.of(step), List.of(tool), AgentResult.Status.COMPLETED,
                new AgentUsage(null, null, null, 0, 1), null, "m19d-deterministic-fixture-v1");
    }

    static String answer(String question, List<RecoveryBenchmarkObservation.Memory> memory, tools.jackson.databind.ObjectMapper mapper) {
        return "Recovery fixture: " + question + "; history=" + RecoveryBenchmarkObservation.hash(mapper, memory);
    }
}
