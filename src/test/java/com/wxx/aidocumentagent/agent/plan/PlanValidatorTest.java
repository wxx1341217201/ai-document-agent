package com.wxx.aidocumentagent.agent.plan;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanValidatorTest {
    private final PlanValidator validator = new PlanValidator(3);
    private final Planner planner = new Planner(new JsonMapper(), validator);

    @Test
    void 非法Json可修复一次且非法action安全失败() {
        Queue<String> replies = new ArrayDeque<>(List.of("not json", """
                {"goal":"比较","steps":[{"id":"S1","action":"SEARCH","query":"A"},
                {"id":"S2","action":"SYNTHESIZE","dependsOn":["S1"]}]}
                """));
        Plan repaired = planner.plan("比较", List.of(), (system, user) ->
                new PlanModelClient.Reply(replies.remove(), null, null, null, null));
        assertThat(repaired.steps()).hasSize(2);

        Queue<String> invalid = new ArrayDeque<>(List.of("""
                {"goal":"比较","steps":[{"id":"S1","action":"EXECUTE_SQL"}]}
                """, """
                {"goal":"比较","steps":[{"id":"S1","action":"EXECUTE_SQL"}]}
                """));
        assertThatThrownBy(() -> planner.plan("比较", List.of(), (system, user) ->
                new PlanModelClient.Reply(invalid.remove(), null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AgentErrorCode.INVALID_PLAN));
    }

    @Test
    void repairFeedbackIdentifiesDependencyErrorWithoutEchoingRawModelOutput() {
        Queue<String> replies = new ArrayDeque<>(List.of("""
                {"goal":"secret-model-output","steps":[
                {"id":"s1","action":"SEARCH","query":"secret-query","dependsOn":["s2"]},
                {"id":"s2","action":"SEARCH","query":"other","dependsOn":["s1"]},
                {"id":"s3","action":"SYNTHESIZE","dependsOn":["s1"]}]}
                """, """
                {"goal":"question","steps":[{"id":"s1","action":"SEARCH","query":"fact"},
                {"id":"s2","action":"SYNTHESIZE","dependsOn":["s1"]}]}
                """));
        java.util.List<String> prompts = new java.util.ArrayList<>();
        var result = planner.plan("question", List.of(), (system, user) -> {
            prompts.add(user);
            return new PlanModelClient.Reply(replies.remove(), null, null, null, null);
        });
        assertThat(result.goal()).isEqualTo("question");
        assertThat(prompts.get(1)).contains("CYCLIC_DEPENDENCIES", "Maximum steps: 3")
                .doesNotContain("secret-query", "secret-model-output");
    }

    @Test
    void 拓扑排序且检测环与超限() {
        Plan unordered = new Plan("比较", List.of(
                new PlanStep("S3", PlanAction.SYNTHESIZE, null, List.of("S2")),
                new PlanStep("S2", PlanAction.FETCH_CONTEXT, null, List.of("S1")),
                new PlanStep("S1", PlanAction.SEARCH, "A", List.of())));
        assertThat(validator.validate(unordered).steps()).extracting(PlanStep::id)
                .containsExactly("S1", "S2", "S3");
        Plan cycle = new Plan("比较", List.of(
                new PlanStep("S1", PlanAction.SEARCH, "A", List.of("S2")),
                new PlanStep("S2", PlanAction.SEARCH, "B", List.of("S1")),
                new PlanStep("S3", PlanAction.SYNTHESIZE, null, List.of("S1"))));
        assertThatThrownBy(() -> validator.validate(cycle)).isInstanceOf(IllegalArgumentException.class);
        Plan tooMany = new Plan("比较", List.of(
                new PlanStep("S1", PlanAction.SEARCH, "A", List.of()),
                new PlanStep("S2", PlanAction.SEARCH, "B", List.of()),
                new PlanStep("S3", PlanAction.SEARCH, "C", List.of()),
                new PlanStep("S4", PlanAction.SYNTHESIZE, null, List.of("S1"))));
        assertThatThrownBy(() -> validator.validate(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesExplicitContextReadInAValidatedPlanWithinTheStepBudget() {
        var result = planner.plan("读取相邻上下文并回答问题", List.of(), (system, user) ->
                new PlanModelClient.Reply("""
                {"goal":"question","steps":[{"id":"required_context","action":"SEARCH","query":"fact"},
                {"id":"answer","action":"SYNTHESIZE","dependsOn":["required_context"]}]}
                """, null, null, null, null));
        assertThat(result.steps()).extracting(PlanStep::action)
                .containsExactly(PlanAction.SEARCH, PlanAction.FETCH_CONTEXT, PlanAction.SYNTHESIZE);
        assertThat(result.steps().get(1).id()).isEqualTo("required_context_1");
        assertThat(result.steps().getLast().dependsOn()).contains("required_context_1");
    }
}
