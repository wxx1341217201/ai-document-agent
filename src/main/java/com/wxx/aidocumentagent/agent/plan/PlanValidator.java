package com.wxx.aidocumentagent.agent.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 检查模型计划并返回稳定的拓扑顺序；非法计划不会触发任何工具。 */
public final class PlanValidator {
    private final int maxSteps;

    public PlanValidator(int maxSteps) {
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps必须大于0");
        this.maxSteps = maxSteps;
    }

    public int maxSteps() { return maxSteps; }

    public Plan validate(Plan plan) {
        if (plan == null || plan.goal() == null || plan.goal().isBlank() || plan.goal().length() > 2_000
                || plan.steps().isEmpty() || plan.steps().size() > maxSteps) {
            throw new IllegalArgumentException("计划目标或步骤数不合法");
        }
        Map<String, PlanStep> byId = new HashMap<>();
        int syntheses = 0;
        for (PlanStep step : plan.steps()) {
            if (step == null || step.id() == null || !step.id().matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
                    || byId.putIfAbsent(step.id(), step) != null || step.dependsOn().size() > maxSteps
                    || new HashSet<>(step.dependsOn()).size() != step.dependsOn().size()) {
                throw new IllegalArgumentException("计划步骤标识或依赖不合法");
            }
            if (step.action() == PlanAction.SEARCH) {
                if (step.query() == null || step.query().isBlank() || step.query().length() > 2_000) {
                    throw new IllegalArgumentException("检索步骤缺少有效查询");
                }
            }
            else if (step.query() != null) {
                throw new IllegalArgumentException("非检索步骤不能携带查询");
            }
            if (step.action() == PlanAction.FETCH_CONTEXT && step.dependsOn().isEmpty()) {
                throw new IllegalArgumentException("上下文步骤必须依赖已检索证据");
            }
            if (step.action() == PlanAction.SYNTHESIZE) {
                syntheses++;
                if (step.dependsOn().isEmpty()) throw new IllegalArgumentException("综合步骤必须依赖证据步骤");
            }
        }
        if (syntheses != 1) throw new IllegalArgumentException("计划必须包含一个综合步骤");
        for (PlanStep step : plan.steps()) {
            for (String dependency : step.dependsOn()) {
                PlanStep source = byId.get(dependency);
                if (source == null || source.action() == PlanAction.SYNTHESIZE
                        || (step.action() == PlanAction.FETCH_CONTEXT && source.action() != PlanAction.SEARCH)) {
                    throw new IllegalArgumentException("步骤依赖不存在或类型不合法");
                }
            }
        }
        List<PlanStep> ordered = new ArrayList<>();
        Map<String, Integer> colors = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            if (step.action() != PlanAction.SYNTHESIZE) visit(step, byId, colors, ordered);
        }
        for (PlanStep step : plan.steps()) {
            if (step.action() == PlanAction.SYNTHESIZE) visit(step, byId, colors, ordered);
        }
        return new Plan(plan.goal().strip(), ordered);
    }

    private void visit(PlanStep step, Map<String, PlanStep> byId, Map<String, Integer> colors,
                       List<PlanStep> ordered) {
        int color = colors.getOrDefault(step.id(), 0);
        if (color == 1) throw new IllegalArgumentException("计划存在循环依赖");
        if (color == 2) return;
        colors.put(step.id(), 1);
        for (String id : step.dependsOn()) visit(byId.get(id), byId, colors, ordered);
        colors.put(step.id(), 2);
        ordered.add(step);
    }
}
