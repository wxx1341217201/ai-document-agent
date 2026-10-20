package com.wxx.aidocumentagent.agent.plan;

import java.util.List;
import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import com.wxx.aidocumentagent.agent.ClaimSupportVerifier;
import com.wxx.aidocumentagent.agent.react.ToolExecutionContext;

/** Runtime semantic feedback with one schema repair, both charged to the outer model budget. */
public final class EvidenceCoverageVerifier {
    public Check verify(String question, Executor.Outcome outcome, ToolExecutionContext context, PlanModelClient model) {
        return verify(question, outcome, context, model, null);
    }
    public Check verify(String question, Executor.Outcome outcome, ToolExecutionContext context, PlanModelClient model,
                        com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.Result binding) {
        if (outcome.answer() == null) return new Check(false, List.of("no cited answer"));
        if (binding != null && !context.fitsEvidenceInput(ClaimSupportVerifier.draftData(binding, context.visible())))
            throw new com.wxx.aidocumentagent.common.api.BusinessException(com.wxx.aidocumentagent.agent.AgentErrorCode.INVALID_ANSWER);
        String user = AgentConversationSupport.prompt(context.history()) + "\nCurrent question: " + question
                + "\nDraft with server-located source quotes:\n" + (binding == null
                        ? AgentConversationSupport.prefix(outcome.answer(), 8_000) : ClaimSupportVerifier.draftData(binding, context.visible()))
                + (binding == null || binding.state() == com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.State.INSUFFICIENT
                        ? "\nActual verified passages:\n" + context.passages() : "");
        var check = new ClaimSupportVerifier().verify(user, (system, input) -> {
            var ids = binding == null || binding.state() == com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.State.INSUFFICIENT
                    ? context.citations().stream().map(com.wxx.aidocumentagent.agent.AgentCitation::citationId).toList()
                    : binding.citations().stream().map(com.wxx.aidocumentagent.agent.AgentCitation::citationId).toList();
            if (binding != null) context.diagnostic("verification_sources", java.util.Map.of("snapshots", ClaimSupportVerifier.fingerprints(binding, context.visible())));
            var reply = model.complete(PlanModelClient.Phase.VERIFY, system
                    + com.wxx.aidocumentagent.agent.EvidenceAnswerSupport.retrievalLanguageHint(
                            context.evidence().stream().map(com.wxx.aidocumentagent.agent.react.EvidenceChunk::text).toList()), input, ids);
            return reply == null ? null : reply.text();
        });
        context.diagnostic("claim_support", java.util.Map.of("verdict", check.verdict(), "missing", check.missing()));
        if (!check.schemaValid()) throw new com.wxx.aidocumentagent.common.api.BusinessException(
                com.wxx.aidocumentagent.agent.AgentErrorCode.INVALID_ANSWER);
        return new Check(check.covered(), check.missing());
    }
    public record Check(boolean covered, List<String> missing) { }
}
