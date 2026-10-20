package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkObservation.*;

/** A confirmed authoritative mismatch is loss. Connectivity/timeouts alone cannot prove permanent loss. */
public final class RecoveryBenchmarkEvaluator {
    public enum Outcome { RECOVERED, SESSION_LOST, RECOVERY_FAILED, NOT_RUN }
    public record Evaluation(Outcome outcome, List<String> reasons) {
        public Evaluation { reasons = List.copyOf(reasons); }
    }

    public Evaluation evaluate(Snapshot before, Snapshot after, boolean faultVerified, Execution checkpoint,
                               Execution recovered, String expectedAnswerSha256, boolean sameRequestExecution) {
        if (!faultVerified || before == null || after == null) {
            return new Evaluation(Outcome.RECOVERY_FAILED, List.of("FAULT_OR_AUTHORITY_NOT_VERIFIED"));
        }
        List<String> lost = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (before.conversationId() != after.conversationId() || before.knowledgeBaseId() != after.knowledgeBaseId()) {
            lost.add("CONVERSATION_KNOWLEDGE_BASE_MISMATCH");
        }
        if (before.lastMessageSequence() != after.lastMessageSequence()) lost.add("MESSAGE_WATERMARK_CHANGED");
        if (!before.messages().equals(after.messages())) lost.add("PERSISTED_MESSAGES_MISSING_CHANGED_OR_REORDERED");
        if (recovered != null) {
            if (checkpoint == null || !Objects.equals(checkpoint.executionId(), recovered.executionId())
                    || recovered.knowledgeBaseId() != before.knowledgeBaseId()
                    || !Objects.equals(recovered.conversationId(), before.conversationId())) lost.add("EXECUTION_SCOPE_CHANGED");
            if (!sameRequestExecution) failed.add("REDELIVERY_CREATED_ANOTHER_EXECUTION");
            if (checkpoint != null && checkpoint.resultSha256() != null
                    && !checkpoint.resultSha256().equals(recovered.resultSha256())) lost.add("COMMITTED_RESULT_CHECKPOINT_CHANGED_OR_MISSING");
            if (checkpoint != null && checkpoint.steps().containsKey("fixture_read")
                    && !recovered.steps().containsKey("fixture_read")) failed.add("COMMITTED_STEP_CHECKPOINT_MISSING");
            if (checkpoint != null) checkpoint.steps().forEach((step, status) -> {
                if (List.of("SUCCEEDED", "COMPLETED").contains(status) && !status.equals(recovered.steps().get(step))) {
                    failed.add("COMPLETED_STEP_STATUS_NOT_RECOVERED:" + step);
                }
            });
            if ("SUCCEEDED".equals(recovered.status()) && !Objects.equals(expectedAnswerSha256, recovered.answerSha256())) {
                lost.add("REPLAY_RESULT_INCONSISTENT");
            }
        }
        if (!lost.isEmpty()) return new Evaluation(Outcome.SESSION_LOST, lost);
        if (!before.memory().equals(after.memory())) failed.add("MEMORY_REBUILD_MISMATCH");
        if (!after.cachePresent()) failed.add("CACHE_REPOPULATION_NOT_VERIFIED");
        if (checkpoint != null && (recovered == null || !"SUCCEEDED".equals(recovered.status()))) {
            failed.add("EXECUTION_DID_NOT_RECOVER_TO_SUCCESS");
        }
        return failed.isEmpty() ? new Evaluation(Outcome.RECOVERED, List.of()) : new Evaluation(Outcome.RECOVERY_FAILED, failed);
    }
}
