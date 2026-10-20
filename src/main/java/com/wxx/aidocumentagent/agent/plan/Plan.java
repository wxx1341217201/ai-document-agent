package com.wxx.aidocumentagent.agent.plan;

import java.util.List;

public record Plan(String goal, List<PlanStep> steps) {
    public Plan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
