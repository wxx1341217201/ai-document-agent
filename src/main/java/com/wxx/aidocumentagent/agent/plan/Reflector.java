package com.wxx.aidocumentagent.agent.plan;

import java.util.ArrayList;
import java.util.List;
import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import com.wxx.aidocumentagent.agent.EvidenceAnswerSupport;

/** 只检查执行状态、证据覆盖和引用校验结果；不请求或记录模型推理。 */
public final class Reflector {
    public Report reflect(Plan plan, Executor.Outcome outcome, int revision, int maxRevisions) {
        List<String> gaps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (step.action() == PlanAction.SYNTHESIZE) continue;
            if (outcome.statuses().get(step.id()) != StepStatus.SUCCEEDED
                    || outcome.evidenceByStep().getOrDefault(step.id(), List.of()).isEmpty()) {
                gaps.add(step.id() + ": " + step.action()
                        + (step.query() == null ? "" : " query=" + AgentConversationSupport.prefix(step.query(), 200))
                        + " has no usable evidence or did not succeed");
            }
        }
        if (outcome.answer() == null || EvidenceAnswerSupport.declaresGap(outcome.answer())) {
            gaps.add("answer: the requested fact is not supported; search specifically for "
                    + AgentConversationSupport.prefix(plan.goal(), 400));
        }
        boolean covered = gaps.isEmpty() && outcome.citationIssues().isEmpty()
                && !outcome.citations().isEmpty();
        boolean revise = !covered && outcome.stopCode() == null && revision < maxRevisions;
        return new Report(covered, List.copyOf(gaps), outcome.citationIssues(), revise);
    }

    public record Report(boolean goalCovered, List<String> evidenceGaps,
                         List<String> citationIssues, boolean needsRevision) {
        public Report {
            evidenceGaps = List.copyOf(evidenceGaps);
            citationIssues = List.copyOf(citationIssues);
        }
    }
}
